package whitelabel.captal.api.routes

import whitelabel.captal.api.session.SessionCookieConfig
import whitelabel.captal.api.wire.given
import whitelabel.captal.api.WireCheck.implementReadable
import whitelabel.captal.api.{ApiBase, ApiErrors, SessionEndpoint}
import whitelabel.captal.core.application.Flow
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.recovery.{ClientReport, ErrorId, Frame}
import java.time.Instant

import whitelabel.captal.messages.{
  ApiError,
  CheckRecoveryRequest,
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
import zio.http.{Method, Routes, Status}

object RecoveryRoutes:
  type RequestRecoveryFlowType = Flow.Aux[Task, RequestRecoveryCommand, RecoveryOutcome]
  type CheckRecoveryFlowType = Flow.Aux[Task, CheckRecoveryCommand, RecoveryOutcome]
  type RedeemRecoveryFlowType = Flow.Aux[Task, RedeemRecoveryCommand, Unit]

  type FullEnv =
    SessionContext & SessionService & QuillSqlite & RequestRecoveryFlowType &
      CheckRecoveryFlowType & RedeemRecoveryFlowType

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
  * for one the page reports; `POST /api/recovery/check` is the waiting page asking where it stands,
  * shortened when another device reported the same error; `POST /api/recovery/redeem` takes it,
  * leaving the session in `Recovered` for `finish`. Any phase: an error can happen in all of them.
  * In the final phases there is no way out, and the answer is `Unrecoverable` with the code staff
  * can look the error up by.
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

  private val check = Endpoint(base(Method.POST / "api" / "recovery" / "check"))
    .header(HeaderCodec.cookie.optional)
    .in[CheckRecoveryRequest]
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

  val routes: Routes[FullEnv, Nothing] = Routes(
    request.implementReadable: (cookie, body) =>
      for
        _       <- session(cookie)
        cause   <- ZIO.fromEither(toCause(body))
        flow    <- ZIO.service[RequestRecoveryFlowType]
        now     <- Clock.instant
        outcome <- asApiError(flow.execute(RequestRecoveryCommand(cause, now)))
        offer   <- offerOf(outcome, now)
      yield offer,
    check.implementReadable: (cookie, body) =>
      for
        _       <- session(cookie)
        errorId <- errorIdOf(body.errorId)
        flow    <- ZIO.service[CheckRecoveryFlowType]
        now     <- Clock.instant
        outcome <- asApiError(flow.execute(CheckRecoveryCommand(errorId, now)))
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
