package whitelabel.captal.api.routes

import whitelabel.captal.api.session.SessionCookieConfig
import whitelabel.captal.api.wire.given
import whitelabel.captal.api.WireCheck.implementReadable
import whitelabel.captal.api.{ApiBase, ApiErrors, RecoveryFeed, SessionEndpoint, WireCheck}
import whitelabel.captal.core.application.Flow
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.recovery.{ClientReport, ErrorId, Frame}
import java.time.Instant

import whitelabel.captal.core.infrastructure.SessionData
import whitelabel.captal.messages.{
  ApiError,
  ClientErrorReport,
  RecoveryOffer,
  RecoveryRequest,
  RedeemRecoveryRequest,
  StatusResponse
}
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.session.{SessionContext, SessionService}
import zio.*
import zio.http.codec.HeaderCodec
import zio.http.endpoint.Endpoint
import zio.http.*
import zio.stream.ZStream

object RecoveryRoutes:
  type RequestRecoveryFlowType = Flow.Aux[Task, RequestRecoveryCommand, RecoveryOutcome]
  type CheckRecoveryFlowType = Flow.Aux[Task, CheckRecoveryCommand, RecoveryOutcome]
  type RedeemRecoveryFlowType = Flow.Aux[Task, RedeemRecoveryCommand, Unit]

  /** What a waiting page's stream reads, for as long as it is open. */
  type StreamEnv = SessionContext & QuillSqlite & CheckRecoveryFlowType & RecoveryFeed

  type FullEnv =
    SessionContext & SessionService & QuillSqlite & RequestRecoveryFlowType &
      CheckRecoveryFlowType & RedeemRecoveryFlowType & RecoveryFeed

  /** How often a waiting page hears something while nothing changes, so nothing between it and this
    * server cuts the stream for silence: CloudFront gives an origin 30 seconds, the load balancer 60.
    */
  val KeepAlive: Duration = 15.seconds

  val layer: ZLayer[SessionCookieConfig & ApiBase, Nothing, RecoveryRoutes] = ZLayer.fromFunction(
    RecoveryRoutes(_, _))

  private[routes] def toCause(request: RecoveryRequest): Either[ApiError, RecoveryCause] =
    (request.serverErrorId, request.report) match
      case (Some(raw), None) =>
        ErrorId.fromString(raw).map(RecoveryCause.Server(_)).toRight(ApiError.UnknownError(raw))
      case (None, Some(report)) =>
        Right(RecoveryCause.Client(toReport(report)))
      case _ =>
        Left(ApiError.InvalidRecoveryRequest("Send either serverErrorId or report, and only one."))

  private def toReport(report: ClientErrorReport): ClientReport = ClientReport(
    report.bundleVersion,
    report.name,
    report.message,
    report.frames.map(f => Frame(f.file, f.line, f.column)))
end RecoveryRoutes

/** A guest's way out of an error, so something breaking never leaves them without internet.
  *
  * `POST /api/recovery` offers it, open at once for an error the server recorded and after a wait
  * for one the page reports; `GET /api/recovery/{errorId}/events` tells the waiting page where it
  * stands as that changes, shortened when another device reported the same error; `POST
  * /api/recovery/redeem` takes it, leaving the session in `Recovered` for `finish`. Any phase: an
  * error can happen in all of them. In the final phases there is no way out, and the answer is
  * `Unrecoverable` with the code staff can look the error up by.
  */
final class RecoveryRoutes(cookieConfig: SessionCookieConfig, base: ApiBase):
  import RecoveryRoutes.*

  private def session(cookie: Option[zio.http.Header.Cookie]) = SessionEndpoint.authorise(
    cookieConfig.valueFrom(cookie),
    SessionEndpoint.OnMissing.Fail,
    Seq.empty)

  private def asApiError[A](result: Task[A]): ZIO[QuillSqlite & SessionContext, ApiError, A] =
    result.catchAllCause: cause =>
      ApiErrors.failWith(
        cause.failureOrCause match
          case Left(error) =>
            error
          case Right(defect) =>
            new Exception(s"Defect: ${defect.prettyPrint}"))

  private val request = Endpoint(base(Method.POST / "api" / "recovery"))
    .header(HeaderCodec.cookie.optional)
    .in[RecoveryRequest]
    .out[RecoveryOffer]
    .outError[ApiError](Status.BadRequest)

  private val redeem = Endpoint(base(Method.POST / "api" / "recovery" / "redeem"))
    .header(HeaderCodec.cookie.optional)
    .in[RedeemRecoveryRequest]
    .out[StatusResponse]
    .outError[ApiError](Status.BadRequest)

  /** `waitSeconds` counted from `now`, the server's clock: the page's cannot be trusted. */
  private def offerOf(outcome: RecoveryOutcome, now: Instant): IO[ApiError, RecoveryOffer] =
    outcome match
      case RecoveryOutcome.Offered(errorId, availableAt, recurrent) =>
        ZIO.succeed(
          RecoveryOffer(
            errorId.asString,
            availableAt.toString,
            math.max(0L, java.time.Duration.between(now, availableAt).toSeconds),
            recurrent))
      case RecoveryOutcome.Unrecoverable(errorId) =>
        ZIO.fail(ApiError.Unrecoverable(errorId.asString))

  private def errorIdOf(raw: String): IO[ApiError, ErrorId] = ZIO
    .fromOption(ErrorId.fromString(raw))
    .orElseFail(ApiError.UnknownError(raw))

  /** Where the session's way out stands now, as [[CheckRecoveryHandler]] has it: shortened, and the
    * shortening recorded, if the error became recurrent. Run in the session it was opened for,
    * which outlives the request that opened the stream.
    */
  private def check(
      session: SessionData,
      errorId: ErrorId): ZIO[StreamEnv, Nothing, Either[ApiError, RecoveryOffer]] =
    ZIO
      .serviceWithZIO[SessionContext]: context =>
        context.locally(session):
          for
            flow    <- ZIO.service[CheckRecoveryFlowType]
            now     <- Clock.instant
            outcome <- asApiError(flow.execute(CheckRecoveryCommand(errorId, now)))
            offer   <- offerOf(outcome, now)
            sent    <- WireCheck.readable(offer)
          yield sent
      .either

  /** Two answers a page would act on alike: the same opening and the same reason for it. */
  private def sameOffer(a: Either[ApiError, RecoveryOffer], b: Either[ApiError, RecoveryOffer]) =
    (a, b) match
      case (Right(x), Right(y)) =>
        x.availableAt == y.availableAt && x.recurrent == y.recurrent
      case _ =>
        false

  private def render(answer: Either[ApiError, RecoveryOffer]): String =
    answer match
      case Right(offer) =>
        s"event: offer\ndata: ${WireCheck.json(offer)}\n\n"
      case Left(error) =>
        s"event: failed\ndata: ${WireCheck.json(error)}\n\n"

  /** The waiting page's stream: an `offer` at once, and again each time an error is recorded, or
    * the feed may have missed one, if that changed it; a `failed` event, with the `ApiError`,
    * ending it when there is no way out left to wait for. Subscribed before the first check, so an
    * error recorded in between is not missed. A comment every [[KeepAlive]] keeps it open
    * meanwhile.
    */
  private[routes] def offers(
      session: SessionData,
      errorId: ErrorId): ZStream[StreamEnv, Nothing, String] = ZStream.unwrapScoped:
    ZIO
      .serviceWithZIO[RecoveryFeed](_.subscribe)
      .map: signals =>
        val answers = (ZStream.unit ++ ZStream.fromQueue(signals).as(()))
          .mapZIO(_ => check(session, errorId))
          .changesWith(sameOffer)
          .takeUntil(_.isLeft)
          .map(render)
        answers.merge(
          ZStream.tick(KeepAlive).drop(1).as(": keepalive\n\n"),
          ZStream.HaltStrategy.Left)

  /** Always `200` and a stream, a refusal included, as a `failed` event: `EventSource` reads no
    * body from any other answer, and would only try again.
    */
  private val events = Routes(
    base(Method.GET / "api" / "recovery" / string("errorId") / "events") ->
      handler: (raw: String, request: Request) =>
        for
          opened <- session(request.header(Header.Cookie)).zip(errorIdOf(raw)).either
          env    <- ZIO.environment[StreamEnv]
        yield Response(
          status = Status.Ok,
          headers = Headers(
            Header.ContentType(MediaType.text.`event-stream`),
            Header.CacheControl.NoCache,
            Header.Custom("X-Accel-Buffering", "no")),
          body = Body.fromCharSequenceStreamChunked(
            opened
              .fold(
                error => ZStream.succeed(render(Left(error))),
                (session, errorId) => offers(session, errorId))
              .provideEnvironment(env))
        ))

  val routes: Routes[FullEnv, Nothing] =
    events ++
      Routes(
        request.implementReadable: (cookie, body) =>
          for
            _       <- session(cookie)
            cause   <- ZIO.fromEither(toCause(body))
            flow    <- ZIO.service[RequestRecoveryFlowType]
            now     <- Clock.instant
            outcome <- asApiError(flow.execute(RequestRecoveryCommand(cause, now)))
            offer   <- offerOf(outcome, now)
          yield offer,
        redeem.implementReadable: (cookie, body) =>
          for
            current <- session(cookie)
            errorId <- errorIdOf(body.errorId)
            flow    <- ZIO.service[RedeemRecoveryFlowType]
            now     <- Clock.instant
            _       <- asApiError(flow.execute(RedeemRecoveryCommand(errorId, now)))
            // The session as the redeem left it: `Recovered`, for `finish` to grant access from.
            fresh <- ZIO
              .serviceWithZIO[SessionService](_.findById(current.sessionId))
              .catchAll(ApiErrors.failWith)
              .someOrFail(ApiError.SessionMissing)
          yield StatusResponse(
            fresh.phase,
            fresh.locale,
            Option(fresh.redirectUrl).filter(_.nonEmpty),
            StatusResponse.secondsRemainingFrom(fresh.accessExpiresAt, now))
      )

end RecoveryRoutes
