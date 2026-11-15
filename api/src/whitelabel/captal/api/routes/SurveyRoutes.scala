package whitelabel.captal.api.routes

import whitelabel.captal.api.session.{CurrentLocation, SessionCookieConfig, UserCookieConfig}
import whitelabel.captal.api.WireCheck.implementReadable
import whitelabel.captal.api.wire.given
import whitelabel.captal.api.{ApiBase, ApiErrors, SessionEndpoint, WireCheck}
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.application.{Flow, NextStep, Phase}
import whitelabel.captal.core.location.LocationId
import whitelabel.captal.core.recovery.ErrorId
import whitelabel.captal.core.user
import whitelabel.captal.messages.{AnswerRequest, ApiError, StatusResponse, SurveyResponse}
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.session.{SessionContext, SessionService}
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
  type EnterPortalFlowType = Flow.Aux[Task, EnterPortalCommand, Entered]

  type FullEnv =
    SessionContext & SessionService & QuillSqlite & AnswerEmailFlowType & AnswerProfilingFlowType &
      AnswerLocationFlowType & NextSurveyFlowType & EnterPortalFlowType

  val layer: ZLayer[
    SessionCookieConfig & ApiBase & CurrentLocation & UserCookieConfig,
    Nothing,
    SurveyRoutes] = ZLayer.fromFunction(SurveyRoutes(_, _, _, _))

end SurveyRoutes

/** The identification survey, and the status route every visit starts with. `/api/status` carries
  * six optional headers and reads two cookies — both out of the one `Cookie` header, via
  * `valueFrom`.
  */
final class SurveyRoutes(
    cookieConfig: SessionCookieConfig,
    base: ApiBase,
    currentLocation: CurrentLocation,
    userCookieConfig: UserCookieConfig):
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

  /** Publishes the session `id` names, when there is one, into `SessionContext`: a server error is
    * recorded against the session in it, which is what lets the guest leave through the error.
    */
  private def publish(
      id: Option[user.SessionId]): ZIO[SessionService & SessionContext, ApiError, Unit] = ZIO
    .foreach(id)(sessionId => ZIO.serviceWithZIO[SessionService](_.findById(sessionId)))
    .mapError(ApiError.fromThrowable)
    .flatMap(found => ZIO.foreachDiscard(found.flatten)(SessionContext.set))

  /** The refusals entering cannot help, which the log has to say: they are an operator's to fix. */
  private def logRefusal(error: ApiError): UIO[Unit] =
    val where = currentLocation.slug.fold("the portal")(slug => s"/$slug")
    error match
      case ApiError.UnknownAccessPoint(ap) =>
        ZIO.logWarning(s"AP $ap is not one of $where's access points: the redirect is refused")
      case ApiError.LocationNotDeployed(_) =>
        ZIO.logWarning(s"$where is not deployed: the guest is refused")
      case _ =>
        ZIO.unit

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
    // Every load of the page runs `EnterPortalCommand`, which decides everything; the route only
    // reads the request into it and its answer out.
    status.implement: (cookies, userAgentOpt, clientMac, apMac, redirectUrl, ssid, fingerprint) =>
      val sessionCookie = cookieConfig.valueFrom(cookies)
      val userCookie = userCookieConfig.valueFrom(cookies)
      for
        sessionId <-
          ZIO.foreach(sessionCookie)(value =>
            ZIO
              .fromOption(user.SessionId.fromString(value))
              .orElseFail(ApiError.SessionInvalid("Malformed session ID")))
        // A failure from here on is recorded against the session the cookie names, so the guest
        // has a way out through it.
        _       <- publish(sessionId)
        flow    <- ZIO.service[EnterPortalFlowType]
        now     <- Clock.instant
        entered <- asApiError(
          flow.execute(
            EnterPortalCommand(
              location = currentLocation.id.map(LocationId(_)),
              redirect = Redirect(
                clientMac,
                apMac,
                redirectUrl.getOrElse(""),
                ssid.getOrElse(""),
                fingerprint),
              sessionId = sessionId,
              userId = userCookie.flatMap(user.Id.fromString),
              anonymous = sessionCookie.isEmpty && userCookie.isEmpty,
              userAgent = userAgentOpt.getOrElse(defaultUserAgent),
              locale = defaultLocale,
              newSession = user.SessionId.generate,
              newDevice = user.DeviceId.generate,
              newError = ErrorId.generate,
              occurredAt = now
            ))).tapError(logRefusal)
        // And from here on, against the one entering started.
        _ <- ZIO.unless(sessionId.contains(entered.sessionId))(publish(Some(entered.sessionId)))
        answer = StatusResponse(
          entered.phase,
          entered.locale,
          entered.redirectUrl,
          StatusResponse.secondsRemainingFrom(entered.accessExpiresAt, now),
          entered.recovery.map(_.asString))
        _ <- WireCheck.readable(answer)
      yield (answer, Some(cookieConfig.asSetCookie(entered.sessionId.asString)))
      end for
  )

end SurveyRoutes
