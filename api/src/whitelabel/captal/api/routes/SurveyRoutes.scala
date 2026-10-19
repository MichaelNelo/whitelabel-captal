package whitelabel.captal.api.routes

import whitelabel.captal.api.session.{
  CurrentLocation,
  SessionCookieConfig,
  UserCookieConfig,
  UserLookup
}
import whitelabel.captal.api.wire.given
import whitelabel.captal.api.{ApiErrors, SessionEndpoint}
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.application.{Flow, NextStep, Phase}
import whitelabel.captal.core.user
import whitelabel.captal.messages.{AnswerRequest, ApiError, StatusResponse, SurveyResponse}
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

  type FullEnv =
    SessionContext & SessionService & UserLookup & AnswerEmailFlowType & AnswerProfilingFlowType &
      AnswerLocationFlowType & NextSurveyFlowType

  val layer: ZLayer[
    SessionCookieConfig & CurrentLocation & UserCookieConfig & UserLookup,
    Nothing,
    SurveyRoutes] = ZLayer.fromFunction(SurveyRoutes(_, _, _, _))

end SurveyRoutes

/** The identification survey, and the status route every visit starts with.
  *
  * The last routes off tapir, and the only ones that used its features rather than just its shape.
  * `/api/status` carries six optional headers and reads two cookies — and the cookies get simpler
  * here, not harder: tapir took one `cookie[Option[String]]` input per cookie, while both arrive in
  * the one `Cookie` header that `valueFrom` picks each out of.
  */
final class SurveyRoutes(
    cookieConfig: SessionCookieConfig,
    currentLocation: CurrentLocation,
    userCookieConfig: UserCookieConfig,
    userLookup: UserLookup):
  import SurveyRoutes.*

  private def identifying(cookie: Option[Header.Cookie]) = SessionEndpoint.authorise(
    cookieConfig.valueFrom(cookie),
    SessionEndpoint.OnMissing.Fail,
    Seq(Phase.IdentificationQuestion))

  /** Whatever a flow failed with, as the `ApiError` the route answers. See `VideoRoutes`. */
  private def asApiError[A](result: Task[A]): IO[ApiError, A] = result.catchAllCause: cause =>
    ApiErrors.failWith(
      cause.failureOrCause match
        case Left(error) =>
          error
        case Right(defect) =>
          new Exception(s"Defect: ${defect.prettyPrint}"))

  // ─── AnswerEmail ──────────────────────────────────────────────────────────

  private val answerEmail = Endpoint(Method.POST / "api" / "survey" / "email")
    .header(HeaderCodec.cookie.optional)
    .in[AnswerRequest]
    .out[SurveyResponse]
    .outHeader(HeaderCodec.setCookie.optional)
    .outError[ApiError](Status.BadRequest)

  // ─── AnswerProfiling ──────────────────────────────────────────────────────

  private val answerProfiling = Endpoint(Method.POST / "api" / "survey" / "profiling")
    .header(HeaderCodec.cookie.optional)
    .in[AnswerRequest]
    .out[SurveyResponse]
    .outError[ApiError](Status.BadRequest)

  // ─── AnswerLocation ───────────────────────────────────────────────────────

  private val answerLocation = Endpoint(Method.POST / "api" / "survey" / "location")
    .header(HeaderCodec.cookie.optional)
    .in[AnswerRequest]
    .out[SurveyResponse]
    .outError[ApiError](Status.BadRequest)

  // ─── NextSurvey ───────────────────────────────────────────────────────────

  private val nextSurvey = Endpoint(Method.GET / "api" / "survey" / "next")
    .header(HeaderCodec.cookie.optional)
    .out[SurveyResponse]
    .outError[ApiError](Status.BadRequest)

  // ─── Status (sets the session cookie) ─────────────────────────────────────

  private val defaultLocale = "es"
  private val defaultUserAgent = "unknown"

  private val status = Endpoint(Method.GET / "api" / "status")
    .header(HeaderCodec.cookie.optional)
    .header(HeaderCodec.name[String]("User-Agent").optional)
    .header(HeaderCodec.name[String]("X-Client-Mac").optional)
    .header(HeaderCodec.name[String]("X-Ap-Mac").optional)
    .header(HeaderCodec.name[String]("X-Redirect-Url").optional)
    .header(HeaderCodec.name[String]("X-Ssid").optional)
    .header(HeaderCodec.name[String]("X-Click-Id").optional)
    .out[StatusResponse]
    .outHeader(HeaderCodec.setCookie.optional)
    .outError[ApiError](Status.BadRequest)

  /** Compare incoming `X-Ap-Mac` against the provisioned `location.ap_mac`.
    *
    * Soft validation: log a warning on mismatch but accept the request. This catches typos in
    * `location.yaml` and surfaces tampered URLs in observability without bricking captive-portal
    * sign-in due to a config error. Promote to a hard fail (403) if/when AP discipline is needed.
    */
  private def softValidateApMac(
      incomingApMac: Option[String],
      clientMac: Option[String]): UIO[Unit] =
    (incomingApMac, currentLocation.apMac, currentLocation.slug) match
      case (Some(incoming), Some(expected), Some(slug)) if !sameMac(incoming, expected) =>
        ZIO.logWarning(
          s"AP MAC mismatch on /$slug: incoming X-Ap-Mac='$incoming' but location.yaml ap_mac='$expected' (clientMac=${clientMac
              .getOrElse("?")})")
      case _ =>
        ZIO.unit

  private def sameMac(a: String, b: String): Boolean = normalize(a) == normalize(b)

  private def normalize(mac: String): String = mac.toLowerCase.replace("-", ":").trim

  /** Resolve the on-missing-session strategy. Prefers a CreateForUser when the cross-location
    * `captal_user` cookie carries a valid, existing user id; falls back to plain Create otherwise.
    */
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
        _          <- identifying(cookie)
        answerFlow <- ZIO.service[AnswerEmailFlowType]
        now        <- Clock.instant
        result     <- asApiError(
          answerFlow.execute(AnswerEmailCommand(request.answer, now)).map(SurveyResponse.from))
        // After flow, SessionContext is updated with the resolved userId by
        // UserPersistenceHandler. Read it to set the cross-location user cookie.
        updated <- SessionContext.get
        userCookie = updated
          .flatMap(_.userId)
          .map(uid => userCookieConfig.asSetCookie(uid.asString))
      yield (result, userCookie),
    answerProfiling.implement: (cookie, request) =>
      for
        _          <- identifying(cookie)
        answerFlow <- ZIO.service[AnswerProfilingFlowType]
        now        <- Clock.instant
        result     <- asApiError(
          answerFlow
            .execute(AnswerProfilingCommand(answer = request.answer, occurredAt = now))
            .map(SurveyResponse.from))
      yield result,
    answerLocation.implement: (cookie, request) =>
      for
        _          <- identifying(cookie)
        answerFlow <- ZIO.service[AnswerLocationFlowType]
        now        <- Clock.instant
        result     <- asApiError(
          answerFlow
            .execute(AnswerLocationCommand(answer = request.answer, occurredAt = now))
            .map(SurveyResponse.from))
      yield result,
    nextSurvey.implement: cookie =>
      for
        session <- SessionEndpoint.authorise(
          cookieConfig.valueFrom(cookie),
          SessionEndpoint.OnMissing.Fail,
          Seq(Phase.Welcome, Phase.IdentificationQuestion))
        // Transition from Welcome to IdentificationQuestion when user requests next survey
        _ <-
          if session.phase == Phase.Welcome then
            ZIO
              .serviceWithZIO[SessionService](
                _.setPhase(session.sessionId, Phase.IdentificationQuestion))
              .mapError(ApiError.fromThrowable)
          else
            ZIO.unit
        flow   <- ZIO.service[NextSurveyFlowType]
        now    <- Clock.instant
        result <- asApiError(
          flow.execute(ProvideNextIdentificationSurveyCommand(now)).map(SurveyResponse.from))
        // Mirror the video route: when the response carries a NextStep, propagate the phase
        // transition to the session so the SPA's follow-up request doesn't fail with a wrong
        // phase. The session-phase handler only reacts to domain events that imply progress
        // (IdentificationCompleted, etc.); a fallback NextStep with no identification work — no
        // surveys seeded for the location, say — emits no such event, so the route owns this.
        _ <-
          result match
            case SurveyResponse.Step(step) =>
              ZIO
                .serviceWithZIO[SessionService](_.setPhase(session.sessionId, step.phase))
                .mapError(ApiError.fromThrowable)
            case _ =>
              ZIO.unit
      yield result,
    status.implement: (cookies, userAgentOpt, clientMac, apMac, redirectUrl, ssid, clickId) =>
      val userAgent = userAgentOpt.getOrElse(defaultUserAgent)
      // Build portalParams only when both clientMac AND click_id are present (both required by
      // the UniFi captive-portal redirect contract). If either is missing on a CREATE flow,
      // session resolution falls through to ApiError.SessionMissing.
      val portalParams =
        (clientMac, clickId) match
          case (Some(mac), Some(cid)) if cid.nonEmpty =>
            Some(
              CaptivePortalParams(
                mac,
                apMac.getOrElse(""),
                redirectUrl.getOrElse(""),
                ssid.getOrElse(""),
                cid))
          case _ =>
            None
      for
        _         <- softValidateApMac(apMac, clientMac)
        onMissing <- resolveOnMissing(userCookieConfig.valueFrom(cookies), userAgent, portalParams)
        session   <- SessionEndpoint.resolveSession(cookieConfig.valueFrom(cookies), onMissing)
        _         <- SessionContext.set(session)
        now       <- Clock.instant
        isExpired =
          session.phase == Phase.Authorized && session.accessExpiresAt.exists(_.isBefore(now))
        response <-
          if isExpired then
            // Reset the session: phase → Welcome, clear userId and current* fields. The user
            // record and the cross-location `captal_user` cookie stay so the next visit skips
            // email.
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
      yield (response, Some(cookieConfig.asSetCookie(session.sessionId.asString)))
      end for
  )

end SurveyRoutes
