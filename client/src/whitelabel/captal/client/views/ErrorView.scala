package whitelabel.captal.client.views
import scala.concurrent.duration.DurationInt
import scala.scalajs.js

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import whitelabel.captal.client.Promises.*
import whitelabel.captal.client.i18n.I18nClient
import whitelabel.captal.client.{AppState, ErrorHandler, Router, Runtime, StatusApi, WelcomeApi}
import whitelabel.captal.core.i18n.I18n
import whitelabel.captal.messages.ApiError

/** Centralized error page, shown on any unexpected error (boot status fail, mid-flow endpoint fail,
  * video load fail, async runtime crash). Copy comes from `i18n.error.{title, generic, retry}`.
  * Validation errors do NOT route here — they stay inline as `.validation-error`.
  */
object ErrorView:
  def render: HtmlElement =
    // True for one tick, so Layout mounts in loading-state and slides the brand icon from the
    // centre to its final position. Combined with `!I18nClient.isLoaded`, a missing locale holds
    // loading until `ensureLocaleLoaded` finishes and Layout transitions exactly once.
    val justMounted: Var[Boolean] = Var(true)
    Layout(
      isLoading = justMounted
        .signal
        .combineWith(I18nClient.isLoaded)
        .map: (just, loaded) =>
          just || !loaded,
      content = div(
        cls := "error-view",
        onMountCallback: _ =>
          ensureLocaleLoaded()
          // Must stay a macrotask, not a microtask and not rAF: those run before the next paint,
          // so `loading-state` would never be painted and the CSS transition would never start.
          js.timers.setTimeout(0.milliseconds)(justMounted.set(false))
        ,
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
  end render

  /** All API errors map to `error.generic`. */
  private def messageFor(error: Option[ApiError], i18n: I18n): String = i18n.error.generic

  /** With no locale loaded every copy binding renders empty, so `/api/locales` is queried and its
    * first entry used; on failure the `localeVar` default ("es") stays.
    */
  private def ensureLocaleLoaded(): Unit =
    if !I18nClient.isLoadedNow then
      Runtime.run:
        WelcomeApi
          .getLocales()
          .map:
            case Right(head :: _) =>
              I18nClient.setLocale(head)
            case _ =>
              ()

  /** Clears the error, re-runs the boot status check and lets the router sync to the phase the
    * server reports. A second failure escalates and stays on this page with the new error.
    */
  private def retry(): Unit =
    AppState.clearError()
    AppState.setNavigating(true)
    val headers = parseCaptivePortalHeaders()
    Runtime.run:
      StatusApi
        .getStatus(headers)
        .map:
          case Right(status) =>
            I18nClient.setLocale(status.locale)
            Router.syncWithPhase(status.phase)
          case Left(err) =>
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
