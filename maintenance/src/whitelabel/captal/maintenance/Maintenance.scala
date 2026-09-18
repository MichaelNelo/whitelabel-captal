package whitelabel.captal.maintenance

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import whitelabel.captal.client.views.Layout

/** What the edge serves in place of the portal while a location is paused.
  *
  * A module of its own rather than a second entrypoint of `client`, which was the obvious shape and
  * turned out to be the expensive one. Scala.js does share internal modules between public modules
  * of one linker run -- measured, the maintenance module came out at 7 KB, only what it adds -- but
  * multiple public modules require `ModuleKind.ESModule`, and the Closure Compiler does not run on
  * ESModule output at all. That took the portal from 247 KB gzipped to 459 KB for every guest, to
  * save on a page almost nobody sees. Two linker runs, each with Closure, leave the portal
  * untouched and cost 54 KB here.
  *
  * `moduleDeps = Seq(client)` is what keeps the reuse: [[Layout]] and the stylesheet are the
  * portal's own, so this looks like the place it stands in front of, and dead-code elimination
  * keeps only what this reaches -- 197 KB against the portal's 908 KB.
  *
  * It talks to nothing, which is the point. This is what gets shown when the backend is
  * deliberately not there, so anything it needed from the API would be missing exactly when it is
  * needed. The copy lives here rather than in the translations for the same reason, and the
  * language comes from the browser.
  */
object Maintenance:

  private case class Copy(title: String, body: String)

  /** Two languages, chosen by prefix so `es-AR` and `en-GB` both land somewhere sensible, with
    * Spanish as the fallback because that is where the locations are.
    */
  private def copyFor(language: String): Copy =
    if language.toLowerCase.startsWith("en") then
      Copy(
        "This portal is not available",
        "The guest WiFi here is out of service for the moment. Nothing is wrong with your device — please try again later.")
    else
      Copy(
        "Este portal no está disponible",
        "El WiFi de este lugar está fuera de servicio por el momento. No es un problema de tu dispositivo: probá de nuevo más tarde."
      )

  private def view(copy: Copy): HtmlElement = Layout(
    content = div(
      cls := "maintenance-view",
      h1(cls := "maintenance-title", copy.title),
      p(cls  := "maintenance-body", copy.body)),
    footer = div())

  def main(args: Array[String]): Unit =
    // The loader is in the markup so something brand-coloured is on screen before this file
    // arrives. Removed rather than faded like the portal does: there is no state to wait for here,
    // and a transition would only delay the one thing this page has to say.
    Option(dom.document.querySelector(".initial-loader")).foreach(_.remove())
    render(dom.document.getElementById("app"), view(copyFor(dom.window.navigator.language)))
end Maintenance
