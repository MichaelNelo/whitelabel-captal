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

  /** Compare the incoming `X-Ap-Mac` against the location's `unifi_ap_mac`. Soft validation: logs a
    * warning on mismatch and accepts the request anyway.
    */
  private def softValidateApMac(incomingApMac: Option[Mac], clientMac: Option[Mac]): UIO[Unit] =
    (incomingApMac, currentLocation.apMac.flatMap(Mac.parse), currentLocation.slug) match
      case (Some(incoming), Some(expected), Some(slug)) if incoming != expected =>
        ZIO.logWarning(
          s"AP MAC mismatch on /$slug: incoming X-Ap-Mac='${incoming
              .show}' but the location's unifi_ap_mac='${expected.show}' (clientMac=${clientMac
              .fold("?")(_.show)})")
      case _ =>
        ZIO.unit

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
        // portalParams only when both a readable clientMac AND a fingerprint are present (both
        // required by the captive-portal redirect contract, the fingerprint unless the location
        // stands one in). If either is missing on a CREATE flow, session resolution falls through
        // to ApiError.SessionMissing.
        portalParams =
          (mac, sent) match
            case (Some(m), Some(fp)) =>
              Some(CaptivePortalParams(m, ap, redirectUrl.getOrElse(""), ssid.getOrElse(""), fp))
            case _ =>
              None
        _         <- softValidateApMac(ap, mac)
        onMissing <- resolveOnMissing(userCookieConfig.valueFrom(cookies), userAgent, portalParams)
        session   <- SessionEndpoint.resolveSession(cookieConfig.valueFrom(cookies), onMissing)
        _         <- SessionContext.set(session)
        now       <- Clock.instant
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
        _ <- WireCheck.readable(gated)
      yield (gated, Some(cookieConfig.asSetCookie(session.sessionId.asString)))
      end for
  )

end SurveyRoutes
