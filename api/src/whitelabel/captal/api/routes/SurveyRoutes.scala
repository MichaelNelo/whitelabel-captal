package whitelabel.captal.api.routes

import whitelabel.captal.api.session.{
  CurrentLocation,
  SessionCookieConfig,
  UserCookieConfig,
  UserLookup
}
import whitelabel.captal.api.WireCheck.implementReadable
import whitelabel.captal.api.wire.given
import whitelabel.captal.api.{ApiBase, ApiErrors, SessionEndpoint, WireCheck}
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.application.{Flow, NextStep, Phase}
import whitelabel.captal.core.device.Mac
import whitelabel.captal.core.infrastructure.SessionData
import whitelabel.captal.core.user
import whitelabel.captal.messages.{AnswerRequest, ApiError, StatusResponse, SurveyResponse}
import whitelabel.captal.platform.Errors
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.services.LocationService
import whitelabel.captal.platform.session.{CaptivePortalParams, SessionContext, SessionService}
import zio.*
import zio.http.codec.HeaderCodec
import zio.http.endpoint.Endpoint
import zio.http.{Header, Method, Routes, Status}

object SurveyRoutes:

  type AnswerEmailFlowType = Flow.Aux[Task, AnswerEmailCommand, NextStep]
  type AnswerProfilingFlowType = Flow.Aux[Task, AnswerProfilingCommand, NextStep]
  type AnswerLocationFlowType = Flow.Aux[Task, AnswerLocationCommand, NextStep]
  type NextSurveyFlowType = Flow.Aux[
    Task,
    ProvideNextIdentificationSurveyCommand,
    NextIdentificationSurvey | NextStep]
  type ResolveWelcomeFlowType = Flow.Aux[Task, ResolveWelcomeCommand, NextStep]

  type FullEnv =
    SessionContext & SessionService & QuillSqlite & UserLookup & AnswerEmailFlowType &
      AnswerProfilingFlowType & AnswerLocationFlowType & NextSurveyFlowType & ResolveWelcomeFlowType

  val layer: ZLayer[
    SessionCookieConfig & ApiBase & CurrentLocation & UserCookieConfig & UserLookup,
    Nothing,
    SurveyRoutes] = ZLayer.fromFunction(SurveyRoutes(_, _, _, _, _))

  /** The fingerprint a guest is given where the location does not require one and the redirect
    * carries none. One value for all of them, so under the default policy, which compares the MAC
    * and the fingerprint, their devices are told apart by the MAC alone; under `ByFingerprint` they
    * would all be one device.
    */
  val NoFingerprint = "no-fingerprint"

  /** The fingerprint a session is created with: the redirect's, or [[NoFingerprint]] where the
    * served location does not require one. The switch is read from the location's row on every
    * call, so changing it needs no restart. Without a location (dev, standalone) one is required.
    */
  private[api] def fingerprintOf(
      quill: QuillSqlite,
      location: CurrentLocation,
      sent: Option[String]): Task[Option[String]] =
    (sent.filter(_.nonEmpty), location.id) match
      case (Some(fingerprint), _) =>
        ZIO.some(fingerprint)
      case (None, Some(id)) =>
        LocationService(quill)
          .fingerprintRequired(id)
          .map(required => Option.unless(required)(NoFingerprint))
      case (None, None) =>
        ZIO.none

end SurveyRoutes

/** The identification survey, and the status route every visit starts with. `/api/status` carries
  * six optional headers and reads two cookies — both out of the one `Cookie` header, via
  * `valueFrom`.
  */
final class SurveyRoutes(
    cookieConfig: SessionCookieConfig,
    base: ApiBase,
    currentLocation: CurrentLocation,
    userCookieConfig: UserCookieConfig,
    userLookup: UserLookup):
  import SurveyRoutes.*

  private def identifying(cookie: Option[Header.Cookie]) = SessionEndpoint.authorise(
    cookieConfig.valueFrom(cookie),
    SessionEndpoint.OnMissing.Fail,
    Seq(Phase.IdentificationQuestion))

  /** Whatever a flow failed with, as the `ApiError` the route answers. See `VideoRoutes`. */
  private def asApiError[A](result: Task[A]): ZIO[QuillSqlite & SessionContext, ApiError, A] =
    result.catchAllCause: cause =>
      ApiErrors.failWith(
        cause.failureOrCause match
          case Left(error) =>
            error
          case Right(defect) =>
            new Exception(s"Defect: ${defect.prettyPrint}"))

  // ─── AnswerEmail ──────────────────────────────────────────────────────────

  private val answerEmail = Endpoint(base(Method.POST / "api" / "survey" / "email"))
    .header(HeaderCodec.cookie.optional)
    .in[AnswerRequest]
    .out[SurveyResponse]
    .outHeader(HeaderCodec.setCookie.optional)
    .outError[ApiError](Status.BadRequest)

  // ─── AnswerProfiling ──────────────────────────────────────────────────────

  private val answerProfiling = Endpoint(base(Method.POST / "api" / "survey" / "profiling"))
    .header(HeaderCodec.cookie.optional)
    .in[AnswerRequest]
    .out[SurveyResponse]
    .outError[ApiError](Status.BadRequest)

  // ─── AnswerLocation ───────────────────────────────────────────────────────

  private val answerLocation = Endpoint(base(Method.POST / "api" / "survey" / "location"))
    .header(HeaderCodec.cookie.optional)
    .in[AnswerRequest]
    .out[SurveyResponse]
    .outError[ApiError](Status.BadRequest)

  // ─── NextSurvey ───────────────────────────────────────────────────────────

  private val nextSurvey = Endpoint(base(Method.GET / "api" / "survey" / "next"))
    .header(HeaderCodec.cookie.optional)
    .out[SurveyResponse]
    .outError[ApiError](Status.BadRequest)

  // ─── Status (sets the session cookie) ─────────────────────────────────────

  private val defaultLocale = "es"
  private val defaultUserAgent = "unknown"

  private val status = Endpoint(base(Method.GET / "api" / "status"))
    .header(HeaderCodec.cookie.optional)
    .header(HeaderCodec.name[String]("User-Agent").optional)
    .header(HeaderCodec.name[String]("X-Client-Mac").optional)
    .header(HeaderCodec.name[String]("X-Ap-Mac").optional)
    .header(HeaderCodec.name[String]("X-Redirect-Url").optional)
    .header(HeaderCodec.name[String]("X-Ssid").optional)
    .header(HeaderCodec.name[String]("X-Fingerprint").optional)
    .out[StatusResponse]
    .outHeader(HeaderCodec.setCookie.optional)
    .outError[ApiError](Status.BadRequest)

  /** A redirect into this location has to name one of its access points: the AP is what says which
    * UniFi controller grants the guest access, so a guest through one that is not the location's
    * could never be authorized. Expected refusals — the page shows them as errors and offers no way
    * out: an AP the location does not have, or none at all, which UniFi always sends. Checked on
    * every request that carries the redirect's parameters, and only by an API serving a location.
    */
  private def requireAccessPoint(
      quill: QuillSqlite,
      portalParams: Option[CaptivePortalParams],
      sentAp: Option[String]): IO[ApiError, Unit] =
    (currentLocation.id, portalParams) match
      case (Some(id), Some(params)) =>
        params.apMac match
          case Some(ap) =>
            LocationService(quill)
              .hasAccessPoint(id, ap)
              .mapError(ApiError.fromThrowable)
              .flatMap: registered =>
                ZIO
                  .when(!registered)(
                    ZIO.logWarning(
                      s"AP ${ap.show} is not one of /${currentLocation
                          .slug
                          .getOrElse(id)}'s access points: the redirect is refused") *>
                      ZIO.fail(ApiError.UnknownAccessPoint(ap.show)))
                  .unit
          case None =>
            ZIO.logWarning(
              s"A redirect into /${currentLocation
                  .slug
                  .getOrElse(id)} names no readable AP ('${sentAp.getOrElse(
                  "")}'): it is refused") *> ZIO.fail(ApiError.InvalidClient(List("X-Ap-Mac")))
        end match
      case _ =>
        ZIO.unit

  /** The redirect's headers that UniFi always sends and this one lacks, or carries unreadable. */
  private def missingOf(clientMac: Option[String], apMac: Option[String]): List[String] = List(
    "X-Client-Mac" -> clientMac,
    "X-Ap-Mac"     -> apMac).collect {
    case (name, value) if value.flatMap(Mac.parse).isEmpty =>
      name
  }

  /** Records that the redirect carried no fingerprint where one is required — the link shortener in
    * front of the portal failing, not the guest — against the session just created, and answers the
    * id the page leaves through. None when it could not be recorded: the guest then goes on through
    * the flow, the session already made.
    */
  private def recordMissingFingerprint(
      quill: QuillSqlite,
      session: SessionData): UIO[Option[String]] =
    val where = currentLocation.slug.fold("the portal")(slug => s"/$slug")
    val message =
      s"The redirect into $where carried no fingerprint, which it requires: the link shortener in front of it may be misconfigured."
    for
      _   <- ZIO.logWarning(message)
      now <- Clock.instant
      id  <- Errors.recordServer(quill, Some(session), message, "", now)
    yield id.map(_.asString)

  /** Resolve the on-missing-session strategy: `CreateForUser` when the cross-location `captal_user`
    * cookie carries a valid, existing user id, plain `Create` otherwise.
    */
  /** The welcome a device is shown, as `ResolveWelcomeHandler` decides it, recorded on its session.
    */
  private def welcomeFor(session: SessionData, anonymous: Boolean) =
    for
      flow <- ZIO.service[ResolveWelcomeFlowType]
      now  <- Clock.instant
      step <- asApiError(flow.execute(ResolveWelcomeCommand(anonymous, now)))
      _    <-
        ZIO.when(step.phase != session.phase)(
          ZIO
            .serviceWithZIO[SessionService](_.setPhase(session.sessionId, step.phase))
            .mapError(ApiError.fromThrowable))
    yield step.phase

  private def resolveOnMissing(
      userCookie: Option[String],
      userAgent: String,
      portalParams: Option[CaptivePortalParams]): UIO[SessionEndpoint.OnMissing] =
    val parsed = userCookie.flatMap(user.Id.fromString)
    (parsed, portalParams) match
      case (Some(uid), Some(_)) =>
        userLookup
          .existsById(uid)
          .map:
            case true =>
              SessionEndpoint.OnMissing.CreateForUser(userAgent, defaultLocale, portalParams, uid)
            case false =>
              SessionEndpoint.OnMissing.Create(userAgent, defaultLocale, portalParams)
      case _ =>
        ZIO.succeed(SessionEndpoint.OnMissing.Create(userAgent, defaultLocale, portalParams))

  // ─── Aggregate ────────────────────────────────────────────────────────────

  val routes: Routes[FullEnv, Nothing] = Routes(
    answerEmail.implement: (cookie, request) =>
      for
        session    <- identifying(cookie)
        answerFlow <- ZIO.service[AnswerEmailFlowType]
        now        <- Clock.instant
        result     <- asApiError(
          answerFlow
            .execute(AnswerEmailCommand(request.answer, session.deviceId, now))
            .map(SurveyResponse.from))
        _ <- WireCheck.readable(result)
        // After flow, SessionContext is updated with the resolved userId by
        // UserPersistenceHandler. Read it to set the cross-location user cookie.
        updated <- SessionContext.get
        userCookie = updated
          .flatMap(_.userId)
          .map(uid => userCookieConfig.asSetCookie(uid.asString))
      yield (result, userCookie),
    answerProfiling.implementReadable: (cookie, request) =>
      for
        _          <- identifying(cookie)
        answerFlow <- ZIO.service[AnswerProfilingFlowType]
        now        <- Clock.instant
        result     <- asApiError(
          answerFlow
            .execute(AnswerProfilingCommand(answer = request.answer, occurredAt = now))
            .map(SurveyResponse.from))
      yield result,
    answerLocation.implementReadable: (cookie, request) =>
      for
        _          <- identifying(cookie)
        answerFlow <- ZIO.service[AnswerLocationFlowType]
        now        <- Clock.instant
        result     <- asApiError(
          answerFlow
            .execute(AnswerLocationCommand(answer = request.answer, occurredAt = now))
            .map(SurveyResponse.from))
      yield result,
    nextSurvey.implementReadable: cookie =>
      for
        // Out of the welcome only through `POST /api/start` or `POST /api/terms` (TermsRoutes).
        session <- SessionEndpoint.authorise(
          cookieConfig.valueFrom(cookie),
          SessionEndpoint.OnMissing.Fail,
          Seq(Phase.IdentificationQuestion))
        flow   <- ZIO.service[NextSurveyFlowType]
        now    <- Clock.instant
        result <- asApiError(
          flow
            .execute(ProvideNextIdentificationSurveyCommand(session.deviceId, now))
            .map(SurveyResponse.from))
        // When the response carries a NextStep, the route propagates the phase to the session:
        // SessionPhaseHandler only reacts to domain events that imply progress
        // (IdentificationCompleted, etc.), and a fallback NextStep emits no such event.
        _ <-
          result match
            case SurveyResponse.Step(step) =>
              ZIO
                .serviceWithZIO[SessionService](_.setPhase(session.sessionId, step.phase))
                .mapError(ApiError.fromThrowable)
            case _ =>
              ZIO.unit
      yield result,
    status.implement: (cookies, userAgentOpt, clientMac, apMac, redirectUrl, ssid, fingerprint) =>
      val userAgent = userAgentOpt.getOrElse(defaultUserAgent)
      val mac = clientMac.flatMap(Mac.parse)
      val ap = apMac.flatMap(Mac.parse)
      for
        quill <- ZIO.service[QuillSqlite]
        sent  <- fingerprintOf(quill, currentLocation, fingerprint).mapError(ApiError.fromThrowable)
        // portalParams whenever the client's MAC is readable: UniFi always sends it, and it is the
        // device access is granted to. The fingerprint comes from the link shortener in front of
        // the portal instead; one that is required and missing is that configuration failing, not
        // the guest, so the session is still made — with the stand-in — and the guest is handed a
        // way out below. Without a MAC there is no device: an unknown session fails as
        // InvalidClient.
        portalParams = mac.map(m =>
          CaptivePortalParams(
            m,
            ap,
            redirectUrl.getOrElse(""),
            ssid.getOrElse(""),
            sent.getOrElse(NoFingerprint)))
        _         <- requireAccessPoint(quill, portalParams, apMac)
        onMissing <- resolveOnMissing(userCookieConfig.valueFrom(cookies), userAgent, portalParams)
        session   <- SessionEndpoint
          .resolveSession(cookieConfig.valueFrom(cookies), onMissing)
          .catchSome:
            case ApiError.SessionMissing if portalParams.isEmpty =>
              ZIO.fail(ApiError.InvalidClient(missingOf(clientMac, apMac)))
        _ <- SessionContext.set(session)
        created = !cookieConfig.valueFrom(cookies).contains(session.sessionId.asString)
        recovery <-
          if created && sent.isEmpty then
            recordMissingFingerprint(quill, session)
          else
            ZIO.none
        now <- Clock.instant
        isExpired =
          session.phase == Phase.Authorized && session.accessExpiresAt.exists(_.isBefore(now))
        response <-
          if isExpired then
            // Reset the session: phase → Welcome, clear the current* fields. It keeps its user,
            // and the cross-location `captal_user` cookie stays, so the next visit skips what was
            // already answered.
            ZIO
              .serviceWithZIO[SessionService](_.resetForExpiration(session.sessionId))
              .mapError(ApiError.fromThrowable)
              .as(StatusResponse(Phase.Welcome, session.locale, None))
          else
            ZIO.succeed(
              StatusResponse(
                session.phase,
                session.locale,
                Option(session.redirectUrl).filter(_.nonEmpty),
                StatusResponse.secondsRemainingFrom(session.accessExpiresAt, now)))
        anonymous =
          cookieConfig.valueFrom(cookies).isEmpty && userCookieConfig.valueFrom(cookies).isEmpty
        gated <-
          if response.phase == Phase.Welcome || response.phase == Phase.WelcomeNeedsToS then
            welcomeFor(session, anonymous).map(phase => response.copy(phase = phase))
          else
            ZIO.succeed(response)
        answer = gated.copy(recovery = recovery)
        _ <- WireCheck.readable(answer)
      yield (answer, Some(cookieConfig.asSetCookie(session.sessionId.asString)))
      end for
  )

end SurveyRoutes
