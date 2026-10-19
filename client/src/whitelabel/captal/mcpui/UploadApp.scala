package whitelabel.captal.mcpui

import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

import org.scalajs.dom

/** The behaviour behind `ui://upload-video`. The markup is the server's; this signs, uploads, and
  * tells the host what happened. One video, one row, already in the HTML — this script doesn't
  * render.
  */
object UploadApp:

  /** Mirrors `EntityResources.UploadTicket`. Field names are the contract between the two.
    */
  final class Ticket(raw: js.Dynamic):
    def title: String = raw.title.asInstanceOf[String]
    def slug: String = raw.slug.asInstanceOf[String]
    def uploadUrl: String = raw.uploadUrl.asInstanceOf[String]
    def expiresInSeconds: Double = raw.expiresInSeconds.asInstanceOf[Double]
    def styles: js.UndefOr[String] = raw.styles.asInstanceOf[js.UndefOr[String]]

  private def byId(id: String) = dom.document.getElementById(id)
  private def ticketUri(videoId: String) = "captal://videos/" + videoId + "/upload"

  /** The page's entry point, exported into a public module of its own rather than run as a `main`.
    *
    * `moduleID = "upload-app"` makes Scala.js emit `upload.js` as a public module separate from the
    * UniFi page's `unifi.js`, out of one linker run.
    *
    * Called by the markup, not invoked by the linker: a `main` runs on module load, and the page
    * decides when to start.
    */
  @JSExportTopLevel(name = "start", moduleID = "upload-app")
  def start(): Unit =
    val row = byId("row")
    Bridge.listen: params =>
      val structured = params.structuredContent.asInstanceOf[js.UndefOr[js.Dynamic]]
      structured.foreach(s => s.videoId.asInstanceOf[js.UndefOr[String]].foreach(start))

    Bridge
      .handshake()
      .`then`[Unit]: _ =>
        // After the handshake, not before: a notification sent to a host that has not acknowledged
        // the frame has nowhere to land.
        Bridge.trackSize()
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
    def refresh(): js.Promise[Unit] = read(videoId).`then`[Unit](fresh => ticket = fresh)
    dom.window.setInterval(() => refresh(), math.max(60.0, ticket.expiresInSeconds - 300) * 1000)

    def finish(code: Int, seconds: Int): Unit =
      if code >= 200 && code < 300 then
        bar.style.width = "100%"
        status.textContent = "Listo. Confirmando…"
        if seconds <= 0 then
          // The one case the page cannot close by itself: the duration is unknown, so the operator
          // must supply it through the model.
          status.textContent = "Subido, pero no pude leer la duración del archivo."
          Bridge.tellTheModel(
            "Subí el archivo del video " + videoId +
              ", pero el navegador no pudo leer su duración. Preguntame cuántos segundos dura y llamá a confirm_video_upload con ese valor.")
        else
          Bridge
            .callTool(
              "confirm_video_upload",
              js.Dynamic.literal(videoId = videoId, durationSeconds = seconds))
            .`then`[Unit]: _ =>
              status.textContent = "Confirmado: " + seconds + " s. El video está en rotación."
              // Tool calls the page makes directly are invisible to the model; this puts the
              // result in the conversation.
              Bridge.tellTheModel(
                "Subí el archivo del video " + videoId + " (" + seconds + " s) y lo confirmé.")
        end if
      else
        button.disabled = false
        status.textContent =
          if code == 0 then
            "No se pudo conectar con S3."
          else
            "S3 rechazó la subida (" + code + ")."

    def upload(chosen: dom.File, seconds: Int): Unit =
      button.disabled = true
      status.textContent = "Subiendo " + chosen.name + "…"
      put(ticket, chosen, bar).`then`[Unit]: code =>
        // A 403 means the signature expired. Re-read once and retry.
        if code == 403 then
          status.textContent = "El enlace había expirado; reintentando…"
          refresh().`then`[Unit](_ =>
            put(ticket, chosen, bar).`then`[Unit](again => finish(again, seconds)))
        else
          finish(code, seconds)

    button.addEventListener("click", (_: dom.Event) => file.click())
    file.addEventListener(
      "change",
      (_: dom.Event) =>
        val files = file.files
        if files.length > 0 then
          // Checked client-side first to avoid a wasted upload; the server checks again at confirm.
          rejectionFor(files(0)) match
            case Some(why) =>
              status.textContent = why
            case None =>
              val chosen = files(0)
              status.textContent = "Leyendo la duración…"
              // Measured before the upload so a wrong file is caught before it's sent.
              durationOf(chosen).`then`[Unit]: seconds =>
                if seconds > 0 then
                  status.textContent = seconds.toString + " s."
                upload(chosen, seconds)
    )
  end wire

  /** The file's length in whole seconds, read from the file itself, or 0 when the browser cannot
    * say (e.g. a streamed WebM with no duration in its header reports `Infinity`).
    *
    * Rounded down: this becomes the default for `minWatchSeconds`, and rounding up could set it
    * past the end of the video.
    */
  private def durationOf(file: dom.File): js.Promise[Int] =
    new js.Promise[Int]((resolve, _) =>
      val probe = dom.document.createElement("video").asInstanceOf[dom.html.Video]
      val url = dom.URL.createObjectURL(file)
      def done(seconds: Int): Unit =
        dom.URL.revokeObjectURL(url)
        resolve(seconds)
      probe.preload = "metadata"
      probe.addEventListener(
        "loadedmetadata",
        (_: dom.Event) =>
          val seconds = probe.duration
          done(
            if seconds.isNaN || seconds.isInfinity || seconds <= 0 then
              0
            else
              math.floor(seconds).toInt))
      probe.addEventListener("error", (_: dom.Event) => done(0))
      probe.src = url)

  /** What the portal can play. Refused before uploading; the server checks again at confirm.
    */
  private def playable(mime: String): Boolean = mime == "video/mp4" || mime == "video/webm"

  /** Refuses only an empty `File.type`: an unrecognised extension leaves it blank, which would send
    * the PUT with no Content-Type and land in S3 as binary/octet-stream.
    */
  private def rejectionFor(file: dom.File): Option[String] =
    if file.`type`.isEmpty then
      Some("No se pudo determinar el tipo del archivo por su extensión. Elegí un .mp4 o un .webm.")
    else
      None

  private def put(ticket: Ticket, file: dom.File, bar: dom.html.Div): js.Promise[Int] =
    new js.Promise[Int]((resolve, _) =>
      val request = new dom.XMLHttpRequest()
      request.open("PUT", ticket.uploadUrl, true)
      // Content-Type set explicitly from the file; the signature binds no type, so this is what
      // gets stored in S3.
      request.setRequestHeader("Content-Type", file.`type`)
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
