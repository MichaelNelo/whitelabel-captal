package whitelabel.captal.client

import whitelabel.captal.messages.ApiError

/** Centralized escalation point for unexpected errors: `case Left(err)` branches, non-API failure
  * handlers (video load events, async exceptions), `Runtime.run` when a Future fails outright. Do
  * NOT use for validation errors — those stay inline via `.validation-error`.
  */
object ErrorHandler:
  def escalate(error: ApiError): Unit =
    // The nav-loader covers the wait for ErrorView's lazy chunk; ErrorView's Layout.onMount turns
    // it off again.
    AppState.setNavigating(true)
    AppState.setError(Some(error))
    Router.navigateToError()

  /** Escalate a non-API failure (video load fail, async exception), wrapped in an InternalError. */
  def escalateMessage(message: String): Unit = escalate(ApiError.InternalError(message))
end ErrorHandler
