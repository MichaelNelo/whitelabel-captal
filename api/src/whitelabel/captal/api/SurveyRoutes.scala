package whitelabel.captal.api

import java.time.Instant

import sttp.tapir.json.circe.*
import sttp.tapir.ztapir.*
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.application.{Flow, NextStep, Phase}
import whitelabel.captal.endpoints.AnswerRequest.given
import whitelabel.captal.endpoints.StatusResponse.given
import whitelabel.captal.endpoints.SurveyResponse.given
import whitelabel.captal.endpoints.schemas.given
import whitelabel.captal.endpoints.{AnswerRequest, ApiError, StatusResponse, SurveyResponse}
import whitelabel.captal.infra.session.{CaptivePortalParams, SessionContext, SessionService}
import zio.*

object SurveyRoutes:

  type AnswerEmailFlowType = Flow.Aux[Task, AnswerEmailCommand, NextStep]
  type AnswerProfilingFlowType = Flow.Aux[Task, AnswerProfilingCommand, NextStep]
  type AnswerLocationFlowType = Flow.Aux[Task, AnswerLocationCommand, NextStep]
  type NextSurveyFlowType = Flow.Aux[
    Task,
    ProvideNextIdentificationSurveyCommand.type,
    NextIdentificationSurvey | NextStep]

  type FullEnv =
    SessionContext & SessionService & AnswerEmailFlowType & AnswerProfilingFlowType &
      AnswerLocationFlowType & NextSurveyFlowType

  val layer
      : ZLayer[SessionEndpoint & SessionCookieConfig & CurrentLocation, Nothing, SurveyRoutes] =
    ZLayer.fromFunction(SurveyRoutes(_, _, _))

end SurveyRoutes

final class SurveyRoutes(
    sessionEndpoint: SessionEndpoint,
    cookieConfig: SessionCookieConfig,
    currentLocation: CurrentLocation):
  import SurveyRoutes.*

  private def toApiError(error: Throwable): UIO[ApiError] =
    error match
      case Flow.HandlerError(errors) =>
        ZIO.succeed(ApiError.fromAppErrors(errors))
      case SessionContext.NotSet =>
        ZIO.succeed(ApiError.SessionMissing)
      case other =>
        ZIO.logErrorCause("Internal error", Cause.fail(other)).as(ApiError.fromThrowable(other))

  // ─── AnswerEmail ──────────────────────────────────────────────────────────

  val answerEmailRoute
      : ZServerEndpoint[SessionContext & SessionService & AnswerEmailFlowType, Any] =
    sessionEndpoint
      .secured(
        onMissingSession = SessionEndpoint.OnMissing.Fail,
        allowedPhases = Seq(Phase.IdentificationQuestion))
      .post
      .in("api" / "survey" / "email")
      .in(jsonBody[AnswerRequest])
      .out(jsonBody[SurveyResponse])
      .serverLogic(session =>
        request =>
          for
            answerFlow <- ZIO.service[AnswerEmailFlowType]
            result     <- answerFlow
              .execute(AnswerEmailCommand(request.answer, Instant.now))
              .map(SurveyResponse.from)
              .catchAllCause: cause =>
                val error =
                  cause.failureOrCause match
                    case Left(e) =>
                      e
                    case Right(c) =>
                      new Exception(s"Defect: ${c.prettyPrint}")
                toApiError(error).flatMap(ZIO.fail(_))
          yield result)

  // ─── AnswerProfiling ──────────────────────────────────────────────────────

  val answerProfilingRoute
      : ZServerEndpoint[SessionContext & SessionService & AnswerProfilingFlowType, Any] =
    sessionEndpoint
      .secured(
        onMissingSession = SessionEndpoint.OnMissing.Fail,
        allowedPhases = Seq(Phase.IdentificationQuestion))
      .post
      .in("api" / "survey" / "profiling")
      .in(jsonBody[AnswerRequest])
      .out(jsonBody[SurveyResponse])
      .serverLogic: _ =>
        request =>
          for
            answerFlow <- ZIO.service[AnswerProfilingFlowType]
            cmd = AnswerProfilingCommand(answer = request.answer, occurredAt = Instant.now)
            result <- answerFlow
              .execute(cmd)
              .map(SurveyResponse.from)
              .catchAllCause: cause =>
                val error =
                  cause.failureOrCause match
                    case Left(e) =>
                      e
                    case Right(c) =>
                      new Exception(s"Defect: ${c.prettyPrint}")
                toApiError(error).flatMap(ZIO.fail(_))
          yield result

  // ─── AnswerLocation ───────────────────────────────────────────────────────

  val answerLocationRoute
      : ZServerEndpoint[SessionContext & SessionService & AnswerLocationFlowType, Any] =
    sessionEndpoint
      .secured(
        onMissingSession = SessionEndpoint.OnMissing.Fail,
        allowedPhases = Seq(Phase.IdentificationQuestion))
      .post
      .in("api" / "survey" / "location")
      .in(jsonBody[AnswerRequest])
      .out(jsonBody[SurveyResponse])
      .serverLogic: _ =>
        request =>
          for
            answerFlow <- ZIO.service[AnswerLocationFlowType]
            cmd = AnswerLocationCommand(answer = request.answer, occurredAt = Instant.now)
            result <- answerFlow
              .execute(cmd)
              .map(SurveyResponse.from)
              .catchAllCause: cause =>
                val error =
                  cause.failureOrCause match
                    case Left(e) =>
                      e
                    case Right(c) =>
                      new Exception(s"Defect: ${c.prettyPrint}")
                toApiError(error).flatMap(ZIO.fail(_))
          yield result

  // ─── NextSurvey ───────────────────────────────────────────────────────────

  val nextSurveyRoute: ZServerEndpoint[SessionContext & SessionService & NextSurveyFlowType, Any] =
    sessionEndpoint
      .secured(
        onMissingSession = SessionEndpoint.OnMissing.Fail,
        allowedPhases = Seq(Phase.Welcome, Phase.IdentificationQuestion))
      .get
      .in("api" / "survey" / "next")
      .out(jsonBody[SurveyResponse])
      .serverLogic: session =>
        _ =>
          for
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
            result <- flow
              .execute(ProvideNextIdentificationSurveyCommand)
              .map(SurveyResponse.from)
              .catchAllCause: cause =>
                val error =
                  cause.failureOrCause match
                    case Left(e) =>
                      e
                    case Right(c) =>
                      new Exception(s"Defect: ${c.prettyPrint}")
                toApiError(error).flatMap(ZIO.fail(_))
          yield result

  // ─── Status (sets the session cookie) ─────────────────────────────────────

  private val defaultLocale = "es"
  private val defaultUserAgent = "unknown"

  import sttp.tapir.header

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

  val statusRoute: ZServerEndpoint[SessionContext & SessionService, Any] = endpoint
    .securityIn(cookieConfig.tapirInput)
    .securityIn(header[Option[String]]("User-Agent"))
    .securityIn(header[Option[String]]("X-Client-Mac"))
    .securityIn(header[Option[String]]("X-Ap-Mac"))
    .securityIn(header[Option[String]]("X-Redirect-Url"))
    .securityIn(header[Option[String]]("X-Ssid"))
    .errorOut(jsonBody[ApiError])
    .zServerSecurityLogic: (cookie, userAgentOpt, clientMac, apMac, redirectUrl, ssid) =>
      val userAgent = userAgentOpt.getOrElse(defaultUserAgent)
      val portalParams = clientMac.map: mac =>
        CaptivePortalParams(mac, apMac.getOrElse(""), redirectUrl.getOrElse(""), ssid.getOrElse(""))
      for
        _       <- softValidateApMac(apMac, clientMac)
        session <- SessionEndpoint.resolveSession(
          cookie,
          SessionEndpoint.OnMissing.Create(userAgent, defaultLocale, portalParams))
        _ <- SessionContext.set(session)
      yield session
    .get
    .in("api" / "status")
    .out(cookieConfig.tapirOutput.and(jsonBody[StatusResponse]))
    .serverLogic: session =>
      _ =>
        ZIO.succeed(
          Some(cookieConfig.asMeta(session.sessionId.asString)),
          StatusResponse(session.phase, session.locale))

  // ─── Aggregate ────────────────────────────────────────────────────────────

  def routes: List[ZServerEndpoint[FullEnv, Any]] = List(
    answerEmailRoute.widen[FullEnv],
    answerProfilingRoute.widen[FullEnv],
    answerLocationRoute.widen[FullEnv],
    nextSurveyRoute.widen[FullEnv],
    statusRoute.widen[FullEnv]
  )

end SurveyRoutes
