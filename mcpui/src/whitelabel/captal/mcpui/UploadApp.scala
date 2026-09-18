package whitelabel.captal.mcpui

import scala.scalajs.js

import org.scalajs.dom

object UploadApp:
  final class Ticket(raw: js.Dynamic):
    def videoId: String = raw.videoId.asInstanceOf[String]
    def title: String = raw.title.asInstanceOf[String]
    def slug: String = raw.slug.asInstanceOf[String]
    def uploadUrl: String = raw.uploadUrl.asInstanceOf[String]
    def contentType: String = raw.contentType.asInstanceOf[String]
    def expiresInSeconds: Double = raw.expiresInSeconds.asInstanceOf[Double]
    def styles: js.UndefOr[String] = raw.styles.asInstanceOf[js.UndefOr[String]]

  private def ticketUri(videoId: String) = "captal://videos/" + videoId + "/upload"
  private val PendingUri = "captal://videos/pending"
  private def byId(id: String) = dom.document.getElementById(id)

  private def readTicket(videoId: String): js.Promise[Ticket] = Bridge
    .readJson(ticketUri(videoId))
    .`then`[Ticket](raw => Ticket(raw))

  def main(args: Array[String]): Unit =
    Bridge.listen: params =>
      val structured = params.structuredContent.asInstanceOf[js.UndefOr[js.Dynamic]]
      structured.foreach(s => s.videoId.asInstanceOf[js.UndefOr[String]].foreach(showOne))
    Bridge.handshake().`then`[Unit](_ => showPending())

  private def showOne(videoId: String): Unit =
    byId("rows").innerHTML = ""
    byId("intro").textContent = "El video queda fuera de rotación hasta que el archivo llegue."
    readTicket(videoId).`then`[Unit] { ticket =>
      byId("rows").appendChild(row(ticket));
      ()
    }

  private def showPending(): Unit = Bridge
    .readJson(PendingUri)
    .`then`[Unit]: listing =>
      val videos = listing.videos.asInstanceOf[js.Array[js.Dynamic]]
      if videos.length == 0 then
        byId("intro").textContent =
          "No hay videos esperando archivo. Creá uno con create_advertiser_video."
      else
        byId("intro").textContent =
          "Cada video queda fuera de rotación hasta que su archivo llegue."
        videos.foreach(v =>
          readTicket(v.videoId.asInstanceOf[String]).`then`[Unit] { ticket =>
            byId("rows").appendChild(row(ticket));
            ()
          })

  private def element(tag: String, cls: String = "", text: String = ""): dom.Element =
    val node = dom.document.createElement(tag)
    if cls.nonEmpty then
      node.setAttribute("class", cls)
    if text.nonEmpty then
      node.textContent = text
    node

  private def row(initial: Ticket): dom.Element =
    var ticket = initial
    ticket.styles.foreach(css => byId("location-css").textContent = css)
    val root = element("div", "row")
    val meta = element("div", "meta")
    meta.appendChild(element("strong", text = ticket.title))
    meta.appendChild(element("span", "slug", ticket.slug))
    val button = element("button", text = "Subir archivo").asInstanceOf[dom.html.Button]
    val input = element("input").asInstanceOf[dom.html.Input]
    input.`type` = "file"
    input.accept = ticket.contentType
    input.style.display = "none"
    val status = element("div", "status")
    val track = element("div", "track")
    val bar = element("div").asInstanceOf[dom.html.Div]
    track.appendChild(bar)
    root.append(meta, button, input, status, track)

    def refresh(): js.Promise[Unit] = readTicket(ticket.videoId).`then`[Unit](fresh =>
      ticket = fresh)
    dom.window.setInterval(() => refresh(), math.max(60.0, ticket.expiresInSeconds - 300) * 1000)

    button.addEventListener("click", (_: dom.Event) => input.click())
    input.addEventListener(
      "change",
      (_: dom.Event) =>
        val files = input.files
        if files.length > 0 then
          val file = files(0)
          button.disabled = true
          status.textContent = "Subiendo " + file.name + "…"
          def finish(code: Int): Unit =
            if code >= 200 && code < 300 then
              bar.style.width = "100%"
              status.textContent = "Listo. Confirmando…"
              Bridge
                .callTool("confirm_video_upload", js.Dynamic.literal(videoId = ticket.videoId))
                .`then`[Unit]: _ =>
                  status.textContent = "Confirmado. El video está en rotación."
                  Bridge.tellTheModel(
                    "Subí el archivo del video " + ticket.videoId + " y lo confirmé.")
            else
              button.disabled = false
              status.textContent =
                if code == 0 then
                  "No se pudo conectar con S3."
                else
                  "S3 rechazó la subida (" + code + ")."
          put(ticket, file, bar).`then`[Unit]: code =>
            if code == 403 then
              status.textContent = "El enlace había expirado; reintentando…"
              refresh().`then`[Unit](_ => put(ticket, file, bar).`then`[Unit](finish))
            else
              finish(code)
        end if
    )
    root
  end row

  private def put(ticket: Ticket, file: dom.File, bar: dom.html.Div): js.Promise[Int] =
    new js.Promise[Int]((resolve, _) =>
      val request = new dom.XMLHttpRequest()
      request.open("PUT", ticket.uploadUrl, true)
      request.setRequestHeader("Content-Type", ticket.contentType)
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
