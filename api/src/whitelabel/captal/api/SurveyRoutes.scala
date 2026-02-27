package whitelabel.captal.api

import java.time.Instant

import sttp.model.headers.CookieValueWithMeta
import sttp.tapir.ztapir.*
import whitelabel.captal.core.application.Flow
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.survey.question.QuestionAnswer
import whitelabel.captal.endpoints.{ApiError, SetLocaleRequest, StatusResponse, SurveyEndpoints}
import whitelabel.captal.infra.{LocaleService, SessionContext, SessionService}
import zio.*

object SurveyRoutes:

  private def toApiError(error: Throwable): UIO[ApiError] =
    error match
      case Flow.HandlerError(errors) =>
        ZIO.succeed(ApiError.fromAppErrors(errors))
      case SessionContext.NotSet =>
        ZIO.succeed(ApiError.SessionMissing)
      case other =>
        ZIO.logErrorCause("Internal error", Cause.fail(other)).as(ApiError.fromThrowable(other))

  type AnswerEmailFlowType = Flow.Aux[Task, AnswerEmailCommand, QuestionAnswer]
  type AnswerProfilingFlowType = Flow.Aux[Task, AnswerProfilingCommand, QuestionAnswer]
  type AnswerLocationFlowType = Flow.Aux[Task, AnswerLocationCommand, QuestionAnswer]
  type NextSurveyFlowType = Flow.Aux[Task, ProvideNextIdentificationSurveyCommand.type, Option[
    NextIdentificationSurvey]]

  object AnswerEmail:
    type Env = SessionContext & SessionService & AnswerEmailFlowType

    val route: ZServerEndpoint[Env, Any] = SurveyEndpoints
      .answerEmail
      .zServerLogic: (cookie, request) =>
        for
          _           <- ZIO.logTrace(s"AnswerEmail: cookie=$cookie")
          sessionData <- SessionEndpoint.resolveSession(cookie, SessionEndpoint.OnMissing.Fail)
          _           <- SessionContext.set(sessionData)
          flow        <- ZIO.service[AnswerEmailFlowType]
          cmd = AnswerEmailCommand(answer = request.answer, occurredAt = Instant.now)
          _ <- flow
            .execute(cmd)
            .unit
            .catchAllCause: cause =>
              val error =
                cause.failureOrCause match
                  case Left(e) =>
                    e
                  case Right(c) =>
                    new Exception(s"Defect: ${c.prettyPrint}")
              ZIO.logError(s"AnswerEmail: cause=${cause.prettyPrint}") *>
                toApiError(error).flatMap(ZIO.fail(_))
        yield ()
  end AnswerEmail

  object AnswerProfiling:
    type Env = SessionContext & SessionService & AnswerProfilingFlowType

    val route: ZServerEndpoint[Env, Any] = SurveyEndpoints
      .answerProfiling
      .zServerLogic: (cookie, request) =>
        for
          sessionData <- SessionEndpoint.resolveSession(cookie, SessionEndpoint.OnMissing.Fail)
          _           <- SessionContext.set(sessionData)
          flow        <- ZIO.service[AnswerProfilingFlowType]
          cmd = AnswerProfilingCommand(answer = request.answer, occurredAt = Instant.now)
          _ <- flow
            .execute(cmd)
            .unit
            .catchAllCause: cause =>
              val error =
                cause.failureOrCause match
                  case Left(e) =>
                    e
                  case Right(c) =>
                    new Exception(s"Defect: ${c.prettyPrint}")
              ZIO.logError(s"AnswerProfiling: cause=${cause.prettyPrint}") *>
                toApiError(error).flatMap(ZIO.fail(_))
        yield ()
  end AnswerProfiling

  object AnswerLocation:
    type Env = SessionContext & SessionService & AnswerLocationFlowType

    val route: ZServerEndpoint[Env, Any] = SurveyEndpoints
      .answerLocation
      .zServerLogic: (cookie, request) =>
        for
          sessionData <- SessionEndpoint.resolveSession(cookie, SessionEndpoint.OnMissing.Fail)
          _           <- SessionContext.set(sessionData)
          flow        <- ZIO.service[AnswerLocationFlowType]
          cmd = AnswerLocationCommand(answer = request.answer, occurredAt = Instant.now)
          _ <- flow
            .execute(cmd)
            .unit
            .catchAllCause: cause =>
              val error =
                cause.failureOrCause match
                  case Left(e) =>
                    e
                  case Right(c) =>
                    new Exception(s"Defect: ${c.prettyPrint}")
              ZIO.logError(s"AnswerLocation: cause=${cause.prettyPrint}") *>
                toApiError(error).flatMap(ZIO.fail(_))
        yield ()
  end AnswerLocation

  object NextSurvey:
    type Env = SessionContext & SessionService & NextSurveyFlowType

    val route: ZServerEndpoint[Env, Any] = SurveyEndpoints
      .nextSurvey
      .zServerLogic: cookie =>
        for
          sessionData <- SessionEndpoint.resolveSession(cookie, SessionEndpoint.OnMissing.Fail)
          _           <- SessionContext.set(sessionData)
          flow        <- ZIO.service[NextSurveyFlowType]
          result      <-
            ZIO.logTrace("NextSurvey: starting execute") *>
              flow
                .execute(ProvideNextIdentificationSurveyCommand)
                .tap(r => ZIO.logTrace(s"NextSurvey: result=$r"))
                .catchAllCause: cause =>
                  val error =
                    cause.failureOrCause match
                      case Left(e) =>
                        e
                      case Right(c) =>
                        new Exception(s"Defect: ${c.prettyPrint}")
                  ZIO.logError(s"NextSurvey: cause=${cause.prettyPrint}") *>
                    toApiError(error).flatMap(ZIO.fail(_))
        yield result
  end NextSurvey

  object Status:
    type Env = SessionContext & SessionService

    val route: ZServerEndpoint[Env, Any] = SurveyEndpoints
      .status
      .zServerLogic: cookie =>
        for
          sessionData <- SessionEndpoint.resolveSession(cookie, SessionEndpoint.OnMissing.Fail)
          _           <- SessionContext.set(sessionData)
        yield StatusResponse(sessionData.phase, sessionData.locale)

  object ListLocales:
    type Env = LocaleService

    val route: ZServerEndpoint[Env, Any] = SurveyEndpoints
      .listLocales
      .zServerLogic: _ =>
        LocaleService.listAvailable().mapError(ApiError.fromThrowable)

  object SetLocale:
    type Env = SessionContext & SessionService

    val route: ZServerEndpoint[Env, Any] = SurveyEndpoints
      .setLocale
      .zServerLogic: (cookie, _, request) =>
        val locale = request.locale
        for
          sessionData <- SessionEndpoint.resolveSession(
            cookie,
            SessionEndpoint
              .OnMissing
              .Create(whitelabel.captal.core.user.DeviceId("unknown"), locale))
          // If session existed, update the locale
          updatedData <-
            if sessionData.locale != locale then
              ZIO
                .serviceWithZIO[SessionService](_.setLocale(sessionData.sessionId, locale))
                .mapError(ApiError.fromThrowable)
                .as(sessionData.copy(locale = locale))
            else
              ZIO.succeed(sessionData)
          _ <- SessionContext.set(updatedData)
        yield CookieValueWithMeta.unsafeApply(updatedData.sessionId.asString)
  end SetLocale

  type FullEnv =
    SessionContext & SessionService & LocaleService & AnswerEmailFlowType &
      AnswerProfilingFlowType & AnswerLocationFlowType & NextSurveyFlowType

  def routes: List[ZServerEndpoint[FullEnv, Any]] = List(
    AnswerEmail.route.widen[FullEnv],
    AnswerProfiling.route.widen[FullEnv],
    AnswerLocation.route.widen[FullEnv],
    NextSurvey.route.widen[FullEnv],
    Status.route.widen[FullEnv],
    ListLocales.route.widen[FullEnv],
    SetLocale.route.widen[FullEnv]
  )
end SurveyRoutes
