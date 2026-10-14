package whitelabel.captal.client
import scala.scalajs.js
import scala.util.Try

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import whitelabel.captal.client.Promises.*
import whitelabel.captal.client.StatusApi

/** Mounts at once, and leaves the markup's loader alone: the first view to mount takes over from it
  * and drops it ([[Layout.adopt]]).
  *
  * This used to fade the loader out and mount 300ms later, to match the CSS transition. The timer
  * had no relation to whether anything was ready, so it removed the only thing on screen at an
  * arbitrary moment and left a blank page until the view arrived. Worse, `Router.lazily` evaluates
  * its split point when the route first renders, so the first view's chunk did not even begin
  * downloading until the timer fired -- the 300ms sat in front of the request rather than
  * overlapping it.
  */
@main
def main(): Unit =
  render(dom.document.getElementById("app"), App.app)
  // Check phase after mount and redirect if needed
  App.syncPhaseOnLoad()
end main

object App:
  /** The nav-loader is a permanent overlay rather than swapped in via `child <--`.
    *
    * There were two reasons and one of them has expired. The first was that `.ring-arc` had to stay
    * in the DOM so `BrandLoader.syncSpin` could copy the spin phase from it on the next mount —
    * both the spinning ring and `syncSpin` are gone, and the waiting animation is `breathe` on the
    * icon, which carries no phase to hand over. The reason that still holds:
    *
    *   - `child <--` removes the previous view before the next one mounts, so a `false → true →
    *     false` cycle on `isNavigating` leaves a paint frame with neither view nor loader on
    *     screen: the "blink". Keeping the outgoing view mounted while the overlay covers it, and
    *     revealing the incoming view once the overlay hides, removes that gap.
    */
  val app: HtmlElement = div(cls := "app-root", child <-- Router.splitter.signal, navigationLoader)

  private def navigationLoader: HtmlElement = div(
    cls := "nav-loader",
    cls("is-visible") <-- AppState.isNavigating,
    BrandLoader())

  /** Extract captive portal params from the UniFi redirect URL.
    *
    * The `url` param (and any other value that survives a UniFi → CloudFront round-trip) can arrive
    * partially percent-encoded — e.g. `http://host%2Fpath` — when an upstream URL-encoded the value
    * once and `URLSearchParams.get` only undoes one layer. We conservatively `decodeURIComponent`
    * each value before forwarding it as an HTTP header so the server stores a canonical URL and
    * `location.assign` later doesn't choke on mixed-encoding forms.
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

  /** decodeURIComponent throws on malformed sequences. We never want a captive-portal value to
    * break boot; on decode failure, fall back to the raw value.
    */
  private def safeDecodeURIComponent(value: String): String = Try(
    js.URIUtils.decodeURIComponent(value)).getOrElse(value)

  /** Check the server-side phase and redirect if the current URL doesn't match. This ensures users
    * entering via direct URLs (e.g., /question, /final) are redirected to the correct phase.
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
