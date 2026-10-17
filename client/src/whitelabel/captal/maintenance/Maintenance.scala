package whitelabel.captal.maintenance

import scala.scalajs.js.annotation.JSExportTopLevel

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import whitelabel.captal.client.views.Layout

/** What the edge serves in place of the portal while a location is paused.
  *
  * A second public module of the portal's own linker run, which is what makes [[Layout]] and the
  * stylesheet literally the portal's rather than a copy that drifts. The linker shares its internal
  * modules across the public modules of one run, and dead-code elimination keeps only what this
  * reaches, so the page costs roughly what it adds.
  *
  * What that gives up is the Closure Compiler: Scala.js runs it only over a single non-ESModule
  * output, and more than one public module requires `ESModule`. On a linker run of its own this
  * page was 54 KB gzipped, against 109 KB once the optimizer was gone -- the portal had already
  * traded Closure away for its route split, so joining it is where that 55 KB goes.
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

  /** Called by the entry stub rather than invoked by the linker: a `main` yields one module
    * initializer per run, and this run has the portal's.
    */
  @JSExportTopLevel(name = "start", moduleID = "maintenance-app")
  def start(): Unit =
    // The loader is in the markup so something brand-coloured is on screen before this file
    // arrives. Nothing to remove it here: [[Layout]] takes over from it on mount, and because this
    // renders with isLoading false the ring resolves straight into the message.
    render(dom.document.getElementById("app"), view(copyFor(dom.window.navigator.language)))
end Maintenance
