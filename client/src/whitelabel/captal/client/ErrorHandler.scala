package whitelabel.captal.client

import whitelabel.captal.messages.ApiError

/** Centralized escalation point for unexpected errors: `case Left(err)` branches and non-API
  * failure handlers (video load events). Do NOT use for validation errors — those stay inline via
  * `.validation-error`. An exception in this code goes to [[Recovery.clientError]] instead.
  */
object ErrorHandler:
  /** An error the server recorded leaves through it ([[Recovery.serverError]]); any other stays on
    * the error view, whose retry is the answer to a lost connection or a refusal.
    */
  def escalate(error: ApiError): Unit =
    error match
      case ApiError.InternalError(_, Some(errorId)) =>
        Recovery.serverError(errorId)
      case _ =>
        // The nav-loader covers the wait for ErrorView's lazy chunk; ErrorView's Layout.onMount
        // turns it off again.
        AppState.setNavigating(true)
        AppState.setError(Some(error))
        Router.navigateToError()

  /** Escalate a non-API failure (video load fail, async exception), wrapped in an InternalError. */
  def escalateMessage(message: String): Unit = escalate(ApiError.InternalError(message))
end ErrorHandler
