package whitelabel.captal.api.routes

import whitelabel.captal.api.WireCheck.implementReadable
import whitelabel.captal.api.session.SessionCookieConfig
import whitelabel.captal.api.wire.given
import whitelabel.captal.api.{ApiBase, ApiErrors, SessionEndpoint}
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.application.{Flow, NextStep, Phase}
import whitelabel.captal.core.infrastructure.SessionData
import whitelabel.captal.messages.{ApiError, TermsResponse}
import whitelabel.captal.platform.Terms
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.session.{SessionContext, SessionService}
import zio.*
import zio.http.codec.HeaderCodec
import zio.http.endpoint.Endpoint
import zio.http.{Method, Routes, Status}

object TermsRoutes:
  type StartFlowType = Flow.Aux[Task, StartCommand, NextStep]
  type AcceptTermsFlowType = Flow.Aux[Task, AcceptTermsCommand, NextStep]
  type FullEnv = SessionContext & SessionService & QuillSqlite & StartFlowType & AcceptTermsFlowType

  val layer: ZLayer[SessionCookieConfig & ApiBase, Nothing, TermsRoutes] = ZLayer.fromFunction(
    TermsRoutes(_, _))
end TermsRoutes

/** The welcome's terms of service. `GET /api/terms` reads the version in force, in the session's
  * locale; `POST /api/terms` accepts it; `POST /api/start` leaves the welcome for a device that
  * already accepted it. The two operations answer the next step, and only they move a session out
  * of the welcome. Rejections answer 400 with an `ApiError` body.
  */
final class TermsRoutes(cookieConfig: SessionCookieConfig, base: ApiBase):
  import TermsRoutes.*

  private val Welcomes = Seq(Phase.Welcome, Phase.WelcomeNeedsToS)

  private val read = Endpoint(base(Method.GET / "api" / "terms"))
    .header(HeaderCodec.cookie.optional)
    .out[TermsResponse]
    .outError[ApiError](Status.BadRequest)

  private val accept = Endpoint(base(Method.POST / "api" / "terms"))
    .header(HeaderCodec.cookie.optional)
    .out[NextStep]
    .outError[ApiError](Status.BadRequest)

  private val start = Endpoint(base(Method.POST / "api" / "start"))
    .header(HeaderCodec.cookie.optional)
    .out[NextStep]
    .outError[ApiError](Status.BadRequest)

  private def welcomed(cookie: Option[zio.http.Header.Cookie], phases: Seq[Phase]) = SessionEndpoint
    .authorise(cookieConfig.valueFrom(cookie), SessionEndpoint.OnMissing.Fail, phases)

  /** Whatever a flow failed with, as the `ApiError` the route answers. See `VideoRoutes`. */
  private def asApiError[A](result: Task[A]): ZIO[QuillSqlite & SessionContext, ApiError, A] =
    result.catchAllCause: cause =>
      ApiErrors.failWith(
        cause.failureOrCause match
          case Left(error) =>
            error
          case Right(defect) =>
            new Exception(s"Defect: ${defect.prettyPrint}"))

  /** The session follows the step the operation answered. */
  private def moveTo(session: SessionData, step: NextStep) = ZIO
    .serviceWithZIO[SessionService](_.setPhase(session.sessionId, step.phase))
    .mapError(ApiError.fromThrowable)
    .as(step)

  val routes: Routes[FullEnv, Nothing] = Routes(
    read.implementReadable: cookie =>
      for
        session      <- welcomed(cookie, Welcomes)
        quill        <- ZIO.service[QuillSqlite]
        terms        <- Terms.current(quill).orDie.someOrFail(ApiError.NoTermsOfService)
        translations <- Terms.translations(quill, terms.id).orDie
        // The portal offers only the locales the terms are written in; the first stands in for a
        // session whose locale is none of them.
        text <- ZIO
          .fromOption(translations.find(_.locale == session.locale).orElse(translations.headOption))
          .orElseFail(ApiError.NoTermsOfService)
      yield TermsResponse(terms.version, text.body),
    accept.implementReadable: cookie =>
      for
        session <- welcomed(cookie, Welcomes)
        flow    <- ZIO.service[AcceptTermsFlowType]
        now     <- Clock.instant
        step    <- asApiError(flow.execute(AcceptTermsCommand(now)))
        moved   <- moveTo(session, step)
      yield moved,
    start.implementReadable: cookie =>
      for
        session <- welcomed(cookie, Seq(Phase.Welcome))
        flow    <- ZIO.service[StartFlowType]
        now     <- Clock.instant
        step    <- asApiError(flow.execute(StartCommand(now)))
        moved   <- moveTo(session, step)
      yield moved
  )
end TermsRoutes
