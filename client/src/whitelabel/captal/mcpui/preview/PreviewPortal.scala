package whitelabel.captal.mcpui.preview

import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

import org.scalajs.dom
import whitelabel.captal.client.Preview
import whitelabel.captal.mcpui.Bridge

/** The behaviour behind `ui://preview-location`, which is `preview.html`: the portal itself,
  * inline, in one document. It talks to the host as every MCP App page does, and when the result of
  * the call that opened it arrives, it links the location's stylesheet, names the location's icon
  * and has [[Preview]] draw the screen the result names.
  */
object PreviewPortal:

  /** A public module of its own, which `vite.preview.config.mjs` bundles alone into one file: the
    * portal's views come with it rather than as the chunks a guest's routes load.
    */
  @JSExportTopLevel(name = "start", moduleID = "preview-portal-app")
  def start(): Unit =
    var shown = false
    Bridge.listen: params =>
      params
        .structuredContent
        .asInstanceOf[js.UndefOr[js.Dynamic]]
        .foreach: structured =>
          if !shown && !js.isUndefined(structured.screen) then
            shown = show(structured)
    // After the handshake, not before: a notification sent to a host that has not acknowledged the
    // frame has nowhere to land. The size reported is the page's fixed height, a phone's.
    val _ = Bridge.handshake().`then`[Unit](_ => Bridge.trackSize())

  /** An absent `Option` arrives as `null` or not at all, depending on the encoder. */
  private def text(value: js.Dynamic): Option[String] = Option(value)
    .filterNot(js.isUndefined)
    .map(_.toString)

  /** Mirrors `PreviewTools.PreviewResponse`. Field names are the contract between the two. */
  private def show(preview: js.Dynamic): Boolean =
    // After the portal's own stylesheet, which is inline in the head, so the location's overrides it.
    text(preview.stylesheet).foreach: url =>
      val link = dom.document.createElement("link")
      link.setAttribute("rel", "stylesheet")
      link.setAttribute("href", url)
      dom.document.head.appendChild(link)
    text(preview.icon).foreach: icon =>
      Option(dom.document.getElementById("captal-preview")).foreach(
        _.setAttribute("data-icon", icon))
    Preview.start(js.JSON.stringify(preview), dom.document.getElementById("app"))
end PreviewPortal
