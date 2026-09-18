package whitelabel.captal.mcpui

import scala.scalajs.js

import org.scalajs.dom

/** The behaviour behind `ui://upload-video`. The markup is the server's; this signs, uploads, and
  * tells the host what happened.
  *
  * No rendering: one video, one row, already in the HTML. What the page cannot know up front is the
  * signed URL — deliberately, so it can ask again when the signature lapses — and, when addressed
  * bare, which video it is at all.
  */
object UploadApp:

  /** Mirrors `EntityResources.UploadTicket`. Field names are the contract between the two, and the
    * reason this is a type: the server renames a field and this stops compiling rather than reading
    * `undefined` at the moment the operator picks a file.
    */
  final class Ticket(raw: js.Dynamic):
    def title: String = raw.title.asInstanceOf[String]
    def slug: String = raw.slug.asInstanceOf[String]
    def uploadUrl: String = raw.uploadUrl.asInstanceOf[String]
    def expiresInSeconds: Double = raw.expiresInSeconds.asInstanceOf[Double]
    def styles: js.UndefOr[String] = raw.styles.asInstanceOf[js.UndefOr[String]]

  private def byId(id: String) = dom.document.getElementById(id)
  private def ticketUri(videoId: String) = "captal://videos/" + videoId + "/upload"

  def main(args: Array[String]): Unit =
    val row = byId("row")
    Bridge.listen: params =>
      val structured = params.structuredContent.asInstanceOf[js.UndefOr[js.Dynamic]]
      structured.foreach(s => s.videoId.asInstanceOf[js.UndefOr[String]].foreach(start))

    Bridge
      .handshake()
      .`then`[Unit]: _ =>
        // Addressed per video the id is in the markup; addressed bare it arrives with the result of
        // the call that opened the page, which is the only channel `_meta.ui.resourceUri` leaves.
        Option(row.getAttribute("data-video")).filter(_.nonEmpty).foreach(start)

  private def start(videoId: String): Unit = read(videoId).`then`[Unit]: ticket =>
    val row = byId("row")
    row.setAttribute("class", "row")
    byId("title").textContent = ticket.title
    byId("slug").textContent = ticket.slug
    byId("intro").textContent = "El video queda fuera de rotación hasta que el archivo llegue."
    ticket.styles.foreach(css => byId("location-css").textContent = css)
    wire(videoId, ticket)

  private def read(videoId: String): js.Promise[Ticket] = Bridge
    .readJson(ticketUri(videoId))
    .`then`[Ticket](raw => Ticket(raw))

  private def wire(videoId: String, initial: Ticket): Unit =
    var ticket = initial
    val button = byId("pick").asInstanceOf[dom.html.Button]
    val file = byId("file").asInstanceOf[dom.html.Input]
    val status = byId("status")
    val bar = byId("bar").asInstanceOf[dom.html.Div]

    // Re-read before the signature lapses rather than letting the operator discover it at the PUT.
    // Asking again is the whole expiry story: a presigned URL is computed, never allocated, so
    // there is nothing to release and nothing for the server to notify about.
    def refresh(): js.Promise[Unit] = read(videoId).`then`[Unit](fresh => ticket = fresh)
    dom.window.setInterval(() => refresh(), math.max(60.0, ticket.expiresInSeconds - 300) * 1000)

    def finish(code: Int): Unit =
      if code >= 200 && code < 300 then
        bar.style.width = "100%"
        status.textContent = "Listo. Confirmando…"
        Bridge
          .callTool("confirm_video_upload", js.Dynamic.literal(videoId = videoId))
          .`then`[Unit]: _ =>
            status.textContent = "Confirmado. El video está en rotación."
            // A call the page makes is invisible to the model, so the agent would keep waiting on
            // something already done. This is what puts it in the conversation.
            Bridge.tellTheModel("Subí el archivo del video " + videoId + " y lo confirmé.")
      else
        button.disabled = false
        status.textContent =
          if code == 0 then
            "No se pudo conectar con S3."
          else
            "S3 rechazó la subida (" + code + ")."

    button.addEventListener("click", (_: dom.Event) => file.click())
    file.addEventListener(
      "change",
      (_: dom.Event) =>
        val chosen = file.files
        if chosen.length > 0 then
          val f = chosen(0)
          button.disabled = true
          status.textContent = "Subiendo " + f.name + "…"
          put(ticket, f, bar).`then`[Unit]: code =>
            // A 403 is what an expired signature looks like. One re-read and one retry, rather than
            // telling the operator to reopen the page.
            if code == 403 then
              status.textContent = "El enlace había expirado; reintentando…"
              refresh().`then`[Unit](_ => put(ticket, f, bar).`then`[Unit](finish))
            else
              finish(code)
    )
  end wire

  private def put(ticket: Ticket, file: dom.File, bar: dom.html.Div): js.Promise[Int] =
    new js.Promise[Int]((resolve, _) =>
      val request = new dom.XMLHttpRequest()
      request.open("PUT", ticket.uploadUrl, true)
      // Not set: XMLHttpRequest sends the File's own type, and the signature binds none, so what
      // S3 records is what the operator actually chose. confirm_video_upload checks it.
      request
        .upload
        .addEventListener(
          "progress",
          (event: dom.ProgressEvent) =>
            if event.lengthComputable then
              bar.style.width = math.round(event.loaded / event.total * 100).toString + "%")
      request.addEventListener("load", (_: dom.Event) => resolve(request.status))
      request.addEventListener("error", (_: dom.Event) => resolve(0))
      request.send(file))
end UploadApp
