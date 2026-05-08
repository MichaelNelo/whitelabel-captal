package whitelabel.captal.client

import whitelabel.captal.endpoints.ApiError

/** Centralized escalation point for unexpected errors. Use from any `case Left(err) => ...`
  * branch (API errors), from non-API failure handlers (video load events, async exceptions),
  * or from `Runtime.run` when a Future fails outright.
  *
  * Do NOT use for validation errors — those stay inline via `.validation-error`.
  */
object ErrorHandler:
  def escalate(error: ApiError): Unit =
    AppState.setError(Some(error))
    Router.navigateToError()

  /** Escalate a non-API failure (video load fail, async exception, etc.). Wraps the message
    * in an InternalError so the rest of the system uses one error type.
    */
  def escalateMessage(message: String): Unit = escalate(ApiError.InternalError(message))
end ErrorHandler
