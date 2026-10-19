package whitelabel.captal.api

import whitelabel.captal.core.application.Flow
import whitelabel.captal.messages.ApiError
import whitelabel.captal.platform.session.SessionContext
import zio.*

/** Shared error translation for HTTP route handlers. */
object ApiErrors:

  /** Translate any throwable into the closed `ApiError` ADT. Unexpected throwables are logged via
    * `Cause.fail`, preserving the stack trace.
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
  def failWith(error: Throwable): IO[ApiError, Nothing] = toApiError(error).flatMap(ZIO.fail(_))

end ApiErrors
