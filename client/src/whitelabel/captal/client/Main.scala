package whitelabel.captal.client
import scala.scalajs.js
import scala.util.Try

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import whitelabel.captal.client.Promises.*
import whitelabel.captal.client.StatusApi

/** Mounts at once, and leaves the markup's loader alone: the first view to mount takes over from it
  * and drops it ([[Layout.adopt]]).
  */
@main
def main(): Unit =
  render(dom.document.getElementById("app"), App.app)
  // Check phase after mount and redirect if needed
  App.syncPhaseOnLoad()
end main

object App:
  /** The nav-loader is a permanent overlay rather than swapped in via `child <--`, so no paint
    * frame is ever left with neither view nor loader on screen.
    */
  val app: HtmlElement = div(cls := "app-root", child <-- Router.splitter.signal, navigationLoader)

  private def navigationLoader: HtmlElement = div(
    cls := "nav-loader",
    cls("is-visible") <-- AppState.isNavigating,
    BrandLoader())

  /** Captive-portal params from the UniFi redirect URL, as HTTP headers. Values can arrive
    * partially percent-encoded (`http://host%2Fpath`), so each is decoded once before forwarding.
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
        Option(params.get(urlParam))
          .filter(_.nonEmpty)
          .map(value => headerName -> safeDecodeURIComponent(value))
      .toMap

  /** `decodeURIComponent` throws on malformed sequences; on failure the raw value is kept. */
  private def safeDecodeURIComponent(value: String): String = Try(
    js.URIUtils.decodeURIComponent(value)).getOrElse(value)

  /** Checks the server-side phase and redirects when the current URL doesn't match it, so entering
    * via a direct URL (e.g. `/question`) still lands on the phase's page.
    */
  def syncPhaseOnLoad(): Unit =
    val headers = parseCaptivePortalHeaders()
    Runtime.run:
      StatusApi
        .getStatus(headers)
        .map:
          case Right(status) =>
            i18n.I18nClient.setLocale(status.locale)
            AppState.setSecondsRemaining(status.secondsRemaining)
            AppState.setPhase(status.phase)
            Router.syncWithPhase(status.phase)
          case Left(err) =>
            ErrorHandler.escalate(err)
end App
