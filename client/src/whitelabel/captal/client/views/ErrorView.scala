package whitelabel.captal.client.views

import scala.concurrent.ExecutionContext.Implicits.global

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import whitelabel.captal.client.i18n.I18nClient
import whitelabel.captal.client.{ApiClient, AppState, ErrorHandler, Router, Runtime}
import whitelabel.captal.core.i18n.I18n
import whitelabel.captal.endpoints.ApiError

/** Centralized error page. Shown when an unexpected API error occurs (boot status fail, mid-flow
  * endpoint fail, video load fail, async runtime crash). All copy comes from `i18n.error.{title,
  * generic, retry}` so it adapts to the active locale.
  *
  * Validation errors do NOT route here — they stay inline as `.validation-error` in the question
  * views.
  */
object ErrorView:
  def render: HtmlElement = Layout(
    content = div(
      cls := "error-view",
      div(
        cls := "error-content",
        div(cls := "error-icon", "⚠"),
        h1(cls  := "error-title", child.text <-- I18nClient.i18n.map(_.error.title)),
        p(
          cls := "error-message",
          child.text <--
            AppState
              .error
              .combineWith(I18nClient.i18n)
              .map: (err, i18n) =>
                messageFor(err, i18n))
      )
    ),
    footer = div(
      button(
        cls := "welcome-button",
        child.text <-- I18nClient.i18n.map(_.error.retry),
        onClick --> { _ =>
          retry()
        }))
  )

  /** All API errors map to `error.generic` for now. Future iterations can switch on ApiError
    * subtypes to distinguish (e.g. `error.network`, `error.unavailable`).
    */
  private def messageFor(error: Option[ApiError], i18n: I18n): String = i18n.error.generic

  /** Retry: clear the error, re-run the boot status check, let the router sync to whatever phase
    * the server reports. If status fails again, ErrorHandler.escalate kicks in and we stay on this
    * page with the new error.
    */
  private def retry(): Unit =
    AppState.clearError()
    AppState.setNavigating(true)
    val headers = parseCaptivePortalHeaders()
    Runtime.run:
      ApiClient
        .getStatus(headers)
        .map:
          case Right(status) =>
            I18nClient.setLocale(status.locale)
            Router.syncWithPhase(status.phase)
            AppState.setNavigating(false)
          case Left(err) =>
            AppState.setNavigating(false)
            ErrorHandler.escalate(err)

  private def parseCaptivePortalHeaders(): Map[String, String] =
    val params = new dom.URLSearchParams(dom.window.location.search)
    List(
      "id"       -> "X-Client-Mac",
      "ap"       -> "X-Ap-Mac",
      "url"      -> "X-Redirect-Url",
      "ssid"     -> "X-Ssid",
      "click_id" -> "X-Click-Id")
      .flatMap: (urlParam, headerName) =>
        Option(params.get(urlParam)).filter(_.nonEmpty).map(headerName -> _)
      .toMap
end ErrorView
