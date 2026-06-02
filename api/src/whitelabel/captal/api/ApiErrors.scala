package whitelabel.captal.api

import whitelabel.captal.core.application.Flow
import whitelabel.captal.endpoints.ApiError
import whitelabel.captal.infra.session.SessionContext
import zio.*

/** Shared error translation for HTTP route handlers.
  *
  * Replaces a `private def toApiError` that was duplicated byte-for-byte across `SurveyRoutes`,
  * `VideoRoutes`, `AdvertiserSurveyRoutes` and `FinishRoutes`. The `failWith` helper folds the
  * common `toApiError(error).flatMap(ZIO.fail(_))` pattern that appeared in every
  * `.catchAllCause` block.
  */
object ApiErrors:

  /** Translate any throwable into the closed `ApiError` ADT. Unexpected throwables are logged via
    * `Cause.fail` so the stack trace is preserved for the operator's log aggregator.
    */
  def toApiError(error: Throwable): UIO[ApiError] =
    error match
      case Flow.HandlerError(errors) =>
        ZIO.succeed(ApiError.fromAppErrors(errors))
      case SessionContext.NotSet =>
        ZIO.succeed(ApiError.SessionMissing)
      case other =>
        ZIO.logErrorCause("Internal error", Cause.fail(other)).as(ApiError.fromThrowable(other))

  /** Translate + fail in one shot. Used by `.catchAllCause` blocks. */
  def failWith(error: Throwable): IO[ApiError, Nothing] =
    toApiError(error).flatMap(ZIO.fail(_))

end ApiErrors
