package whitelabel.captal.mcpui

import scala.scalajs.js

import com.raquo.laminar.api.L.*
import org.scalajs.dom

/** What `ui://upload-video` runs.
  *
  * The page ships no data: the markup is static and every signed URL is read at the moment it is
  * needed. That is deliberate — an earlier version embedded one URL per pending video and hid the
  * rows it had not been opened for, which is not the same as withholding them.
  */
object UploadApp:

  /** Mirrors `EntityResources.UploadTicket`. Field names are the contract between the two, and the
    * reason this is worth having as a type: the server renames a field and this stops compiling
    * rather than reading `undefined` at the moment the operator picks a file.
    */
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

  private val intro = Var("Cargando…")
  private val rows = Var(List.empty[Ticket])

  private def readTicket(videoId: String): js.Promise[Ticket] = Bridge
    .readJson(ticketUri(videoId))
    .`then`[Ticket](raw => Ticket(raw))

  def main(args: Array[String]): Unit =
    Bridge.listen: params =>
      val structured = params.structuredContent.asInstanceOf[js.UndefOr[js.Dynamic]]
      structured.foreach(s => s.videoId.asInstanceOf[js.UndefOr[String]].foreach(showOne))

    render(dom.document.getElementById("app"), page)
    Bridge.handshake().`then`[Unit](_ => showPending())

  private def page: HtmlElement = div(
    cls := "upload",
    h1("Subir video"),
    p(cls := "intro", child.text <-- intro.signal),
    div(children <-- rows.signal.map(_.map(row))))

  /** Opened by a call: the one video it produced, and nothing else. */
  private def showOne(videoId: String): Unit =
    intro.set("El video queda fuera de rotación hasta que el archivo llegue.")
    readTicket(videoId).`then`[Unit](ticket => rows.set(List(ticket)))

  /** Opened on its own: everything still outstanding, still one signed URL per row and none of them
    * in the markup.
    */
  private def showPending(): Unit = Bridge
    .readJson(PendingUri)
    .`then`[Unit]: listing =>
      val videos = listing.videos.asInstanceOf[js.Array[js.Dynamic]]
      if videos.length == 0 then
        intro.set("No hay videos esperando archivo. Creá uno con create_advertiser_video.")
      else
        intro.set("Cada video queda fuera de rotación hasta que su archivo llegue.")
        videos.foreach(v =>
          readTicket(v.videoId.asInstanceOf[String]).`then`[Unit](ticket =>
            rows.update(_ :+ ticket)))

  private def row(initial: Ticket): HtmlElement =
    var ticket = initial
    ticket.styles.foreach(css => dom.document.getElementById("location-css").textContent = css)

    val status = Var("")
    val progress = Var(0)
    val busy = Var(false)
    val fileInput = input(cls := "file", tpe := "file", accept := ticket.contentType)

    // Re-read before the signature lapses rather than letting the operator discover it at the PUT.
    // Asking again is the whole expiry story: a presigned URL is computed, never allocated, so
    // there is nothing to release and nothing for the server to notify about.
    def refresh(): js.Promise[Unit] = readTicket(ticket.videoId).`then`[Unit](fresh =>
      ticket = fresh)
    dom.window.setInterval(() => refresh(), math.max(60.0, ticket.expiresInSeconds - 300) * 1000)

    def finish(code: Int): Unit =
      if code >= 200 && code < 300 then
        progress.set(100)
        status.set("Listo. Confirmando…")
        Bridge
          .callTool("confirm_video_upload", js.Dynamic.literal(videoId = ticket.videoId))
          .`then`[Unit]: _ =>
            status.set("Confirmado. El video está en rotación.")
            // A call the page makes is invisible to the model, so the agent would keep waiting on
            // something already done. This is what puts it in the conversation.
            Bridge.tellTheModel("Subí el archivo del video " + ticket.videoId + " y lo confirmé.")
      else
        busy.set(false)
        status.set(
          if code == 0 then
            "No se pudo conectar con S3."
          else
            "S3 rechazó la subida (" + code + ").")

    def upload(file: dom.File): Unit =
      busy.set(true)
      status.set("Subiendo " + file.name + "…")
      put(ticket, file, progress).`then`[Unit]: code =>
        // A 403 is what an expired signature looks like. One re-read and one retry, rather than
        // telling the operator to reopen the page.
        if code == 403 then
          status.set("El enlace había expirado; reintentando…")
          refresh().`then`[Unit](_ => put(ticket, file, progress).`then`[Unit](finish))
        else
          finish(code)

    div(
      cls := "row",
      div(cls := "meta", strong(ticket.title), span(cls := "slug", ticket.slug)),
      button(
        cls := "pick",
        disabled <-- busy.signal,
        "Subir archivo",
        onClick --> (_ => fileInput.ref.click())),
      fileInput.amend(onChange --> (_ => fileInput.ref.files.headOption.foreach(upload))),
      div(cls := "status", child.text <-- status.signal),
      div(cls := "track", div(cls := "bar", width <-- progress.signal.map(_.toString + "%")))
    )
  end row

  private def put(ticket: Ticket, file: dom.File, progress: Var[Int]): js.Promise[Int] =
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
              progress.set(math.round(event.loaded / event.total * 100).toInt))
      request.addEventListener("load", (_: dom.Event) => resolve(request.status))
      request.addEventListener("error", (_: dom.Event) => resolve(0))
      request.send(file))
end UploadApp
