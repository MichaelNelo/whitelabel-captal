package whitelabel.captal.client

import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js
import scala.util.Try

import com.raquo.laminar.api.L.*
import org.scalajs.dom

@main
def main(): Unit =
  val appContainer = dom.document.getElementById("app")
  val loader = Option(dom.document.querySelector(".initial-loader"))

  // Fade out initial loader, then mount Laminar
  loader match
    case Some(el) =>
      el.classList.add("fade-out")
      dom
        .window
        .setTimeout(
          () =>
            el.remove()
            render(appContainer, App.app)
            // Check phase after mount and redirect if needed
            App.syncPhaseOnLoad()
          ,
          300 // Match --transition-slow
        )
    case None =>
      render(appContainer, App.app)
      App.syncPhaseOnLoad()
end main

object App:
  val app: HtmlElement = div(
    cls := "app-root",
    child <--
      AppState
        .isNavigating
        .combineWith(Router.splitter.signal)
        .map:
          case (true, _) =>
            navigationLoader
          case (false, view) =>
            view)

  private def navigationLoader: HtmlElement = div(
    cls := "nav-loader",
    div(
      cls := "loader-icon brand-pulse",
      img(src := "brand-icon.svg", cls := "brand-icon", alt := "Loading")))

  /** Extract captive portal params from the UniFi redirect URL.
    *
    * The `url` param (and any other value that survives a UniFi → CloudFront round-trip)
    * can arrive partially percent-encoded — e.g. `http://host%2Fpath` — when an upstream
    * URL-encoded the value once and `URLSearchParams.get` only undoes one layer. We
    * conservatively `decodeURIComponent` each value before forwarding it as an HTTP
    * header so the server stores a canonical URL and `location.assign` later doesn't
    * choke on mixed-encoding forms.
    */
  private def parseCaptivePortalHeaders(): Map[String, String] =
    val params = new dom.URLSearchParams(dom.window.location.search)
    List(
      "id"       -> "X-Client-Mac",
      "ap"       -> "X-Ap-Mac",
      "url"      -> "X-Redirect-Url",
      "ssid"     -> "X-Ssid",
      "click_id" -> "X-Click-Id")
      .flatMap: (urlParam, headerName) =>
        Option(params.get(urlParam)).filter(_.nonEmpty).map(value =>
          headerName -> safeDecodeURIComponent(value))
      .toMap

  /** decodeURIComponent throws on malformed sequences. We never want a captive-portal
    * value to break boot; on decode failure, fall back to the raw value.
    */
  private def safeDecodeURIComponent(value: String): String =
    Try(js.URIUtils.decodeURIComponent(value)).getOrElse(value)

  /** Check the server-side phase and redirect if the current URL doesn't match. This ensures users
    * entering via direct URLs (e.g., /question, /final) are redirected to the correct phase.
    */
  def syncPhaseOnLoad(): Unit =
    val headers = parseCaptivePortalHeaders()
    Runtime.run:
      ApiClient
        .getStatus(headers)
        .map:
          case Right(status) =>
            i18n.I18nClient.setLocale(status.locale)
            AppState.setAccessExpiresAt(status.accessExpiresAt)
            AppState.setPhase(status.phase)
            Router.syncWithPhase(status.phase)
          case Left(err) =>
            ErrorHandler.escalate(err)
end App
