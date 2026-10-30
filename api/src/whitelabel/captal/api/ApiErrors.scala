package whitelabel.captal.api

import whitelabel.captal.core.application.Flow
import whitelabel.captal.messages.ApiError
import whitelabel.captal.platform.Errors
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.session.SessionContext
import zio.*

/** Shared error translation for HTTP route handlers. */
object ApiErrors:

  /** What a route answers for a failure. A handler's typed refusal is the guest's own doing;
    * anything else is a defect of ours, logged with its stack, recorded against the session in
    * context, and answered with the id it was recorded under — which is what the page asks for a
    * way out with.
    */
  def toApiError(error: Throwable): URIO[QuillSqlite & SessionContext, ApiError] =
    error match
      case Flow.HandlerError(errors) =>
        ZIO.succeed(ApiError.fromAppErrors(errors))
      case SessionContext.NotSet =>
        ZIO.succeed(ApiError.SessionMissing)
      case other =>
        val message = Option(other.getMessage).getOrElse(other.getClass.getName)
        for
          _       <- ZIO.logErrorCause("Internal error", Cause.fail(other))
          session <- SessionContext.get
          quill   <- ZIO.service[QuillSqlite]
          now     <- Clock.instant
          errorId <- Errors.recordServer(quill, session, message, stackOf(other), now)
        yield ApiError.InternalError(message, errorId.map(_.asString))

  /** Translate + fail in one shot. Used by `.catchAllCause` blocks. */
  def failWith(error: Throwable): ZIO[QuillSqlite & SessionContext, ApiError, Nothing] = toApiError(
    error).flatMap(ZIO.fail(_))

  private def stackOf(error: Throwable): String =
    val writer = new java.io.StringWriter
    error.printStackTrace(new java.io.PrintWriter(writer))
    writer.toString

end ApiErrors
