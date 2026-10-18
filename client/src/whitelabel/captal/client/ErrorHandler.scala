package whitelabel.captal.client

import whitelabel.captal.messages.ApiError

/** Centralized escalation point for unexpected errors. Use from any `case Left(err) => ...` branch
  * (API errors), from non-API failure handlers (video load events, async exceptions), or from
  * `Runtime.run` when a Future fails outright.
  *
  * Do NOT use for validation errors — those stay inline via `.validation-error`.
  */
object ErrorHandler:
  def escalate(error: ApiError): Unit =
    // Turn the nav-loader on before navigating to Error: ErrorView's lazy chunk takes a moment to
    // come down, and without the overlay the user keeps seeing the view the error happened in (or
    // an empty frame) until Error mounts. ErrorView's Layout.onMount turns it off on its own.
    AppState.setNavigating(true)
    AppState.setError(Some(error))
    Router.navigateToError()

  /** Escalate a non-API failure (video load fail, async exception, etc.). Wraps the message in an
    * InternalError so the rest of the system uses one error type.
    */
  def escalateMessage(message: String): Unit = escalate(ApiError.InternalError(message))
end ErrorHandler
