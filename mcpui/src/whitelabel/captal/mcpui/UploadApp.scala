package whitelabel.captal.mcpui

import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

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

  /** The page's entry point, exported into a public module of its own rather than run as a `main`.
    *
    * `moduleID = "upload-app"` is what makes Scala.js emit `upload.js` as a public module separate
    * from the UniFi page's `unifi.js`, out of one linker run — and that is the whole reason the two
    * apps now live in one module. One run hoists what they share, the `Bridge` protocol and the
    * Scala.js runtime, into a chunk both entries import; a chunk has a hashed name and is cacheable
    * forever. Two runs produced two self-contained files carrying about 3.6 KB gzip of identical
    * code each, which no amount of caching could deduplicate.
    *
    * Called by the markup instead of invoked by the linker: a `main` runs on module load, and the
    * page wants to decide when to start.
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
    // Asking again is the whole expiry story: a presigned URL is computed, never allocated, so
    // there is nothing to release and nothing for the server to notify about.
    def refresh(): js.Promise[Unit] = read(videoId).`then`[Unit](fresh => ticket = fresh)
    dom.window.setInterval(() => refresh(), math.max(60.0, ticket.expiresInSeconds - 300) * 1000)

    def finish(code: Int, seconds: Int): Unit =
      if code >= 200 && code < 300 then
        bar.style.width = "100%"
        status.textContent = "Listo. Confirmando…"
        if seconds <= 0 then
          // The one case the page cannot close by itself. The file is in S3 and the row is still
          // pending, so nothing is lost — but the duration has to come from somewhere, and the
          // operator is the only one who knows it. Handing it to the model with the video id is
          // what lets the conversation finish what the page started.
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
              // A call the page makes is invisible to the model, so the agent would keep waiting on
              // something already done. This is what puts it in the conversation.
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
        // A 403 is what an expired signature looks like. One re-read and one retry, rather than
        // telling the operator to reopen the page.
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
          // Checked here so a wrong file costs nothing: no upload, no round trip, and the operator
          // is told while the file dialog is still fresh in mind. The server checks again at
          // confirm, because this page is not the only thing holding the signed URL.
          rejectionFor(files(0)) match
            case Some(why) =>
              status.textContent = why
            case None =>
              val chosen = files(0)
              status.textContent = "Leyendo la duración…"
              // Measured before the upload rather than after, so the operator sees it next to the
              // file they just picked. A length that reads wrong here is a wrong file, and that is
              // worth catching before a hundred megabytes go up.
              durationOf(chosen).`then`[Unit]: seconds =>
                if seconds > 0 then
                  status.textContent = seconds.toString + " s."
                upload(chosen, seconds)
    )
  end wire

  /** The file's length in whole seconds, or 0 when the browser cannot say.
    *
    * Read from the file itself, which is the only place the answer actually lives. It used to be an
    * argument to `create_advertiser_video`, supplied before any file existed by whoever was
    * describing the video — and a wrong number there is invisible in the backoffice and visible to
    * guests as a countdown that ends in the wrong place.
    *
    * Rounded down. The survey unlocks at `minWatchSeconds`, which defaults to this, and rounding up
    * would set it past the end of a video that stops a fraction of a second earlier — a condition
    * that is never met.
    *
    * Zero for the cases the browser genuinely cannot answer: a WebM written as a stream carries no
    * duration in its header and reports `Infinity`. Reporting that as a number would be worse than
    * admitting it.
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

  /** What the portal can play. The page refuses anything else before uploading; the server checks
    * again at confirm, because the page is not the only thing holding the signed URL.
    *
    * Two comparisons rather than a `Set`: a Set of two strings pulls Scala's immutable collections
    * into the bundle, which measured at 146 KB against 23 KB for this.
    */
  private def playable(mime: String): Boolean = mime == "video/mp4" || mime == "video/webm"

  /** The only thing worth refusing here, and it is not a judgement about the file: an unrecognised
    * extension leaves `File.type` empty, which would send the PUT with no Content-Type at all and
    * have S3 record binary/octet-stream. Everything else the browser reports is passed through — it
    * is a fact about the file the operator chose, and checking it against a list would be guessing
    * at something already known.
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
      // Explicitly the file's own type rather than letting XHR infer it, so an empty one is caught
      // above instead of arriving at S3 as binary/octet-stream. The signature binds no type, so
      // this is what gets stored.
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
