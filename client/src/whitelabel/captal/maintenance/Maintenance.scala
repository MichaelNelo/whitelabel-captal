package whitelabel.captal.maintenance

import scala.scalajs.js.annotation.JSExportTopLevel

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import whitelabel.captal.client.views.Layout

/** What the edge serves in place of the portal while a location is paused.
  *
  * A second public module of the portal's own linker run, sharing [[Layout]] and the stylesheet
  * with it. It must talk to nothing: it is shown precisely when the backend is not there, so the
  * copy lives here rather than in the translations and the language comes from the browser.
  */
object Maintenance:

  private case class Copy(title: String, body: String)

  /** Two languages, chosen by language-tag prefix (`es-AR`, `en-GB`), with Spanish as fallback. */
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

  /** Called by the entry stub `client/entries/maintenance.js`, not invoked by the linker: a `main`
    * yields one module initializer per run, and this run has the portal's.
    */
  @JSExportTopLevel(name = "start", moduleID = "maintenance-app")
  def start(): Unit =
    // The markup's loader is not removed here: Layout takes over from it on mount, and rendering
    // with isLoading false resolves the ring straight into the message.
    render(dom.document.getElementById("app"), view(copyFor(dom.window.navigator.language)))
end Maintenance
