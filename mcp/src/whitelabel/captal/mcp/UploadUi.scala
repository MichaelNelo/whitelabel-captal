package whitelabel.captal.mcp

import scalatags.Text.all.*
import scalatags.Text.tags2.{style as styleTag, title as titleTag}

/** The upload page, rendered server-side.
  *
  * An MCP App: `create_advertiser_video` points at `ui://upload-video` through `_meta.ui`, the host
  * reads that resource and renders the HTML in a sandboxed iframe, and the page PUTs the file
  * straight to the presigned URL. The bytes never pass through this server, which is the whole
  * reason the page exists — a video cannot travel over JSON-RPC.
  *
  * Built with scalatags rather than a template file so the markup is type-checked and cannot drift
  * away from the data it renders.
  *
  * The location's stylesheet is inlined rather than linked. The iframe is sandboxed with a
  * restrictive CSP, and a stylesheet fetched from the CDN would need that origin allowed; inlining
  * sidesteps the question entirely and costs one round trip less on a page the operator sees once.
  */
object UploadUi:
  val Uri: String = "ui://upload-video"

  /** The page's behaviour, linked from the `mcpui` Scala.js module and inlined here.
    *
    * It was 145 lines of JavaScript in a string literal. The move is not cosmetic: the two bugs
    * that cost this migration a session were a `postMessage` envelope no host reads and a missing
    * `ui/message`, and neither is the kind of thing a string literal can be wrong about loudly. The
    * bridge is now a type, and `UploadTicket`'s field names are checked against something.
    *
    * Inlined rather than served, because the iframe's CSP blocks every external host.
    */
  private lazy val appScript: String =
    val stream = getClass.getResourceAsStream("/upload-app.js")
    if stream == null then
      throw new IllegalStateException(
        "upload-app.js is missing from the jar. It is produced by `mcpui.fullLinkJS` and copied in `mcp.resources`.")
    else
      try new String(stream.readAllBytes(), "UTF-8")
      finally stream.close()

  /** The page's own styling, kept minimal so the location's stylesheet decides how it looks. The
    * variables are the ones `style-reference` documents, so a location that themed the portal
    * themes this too without knowing it exists.
    */
  private val baseCss =
    """
    |body { margin: 0; padding: 2rem; font-family: var(--font-body, system-ui, sans-serif);
    |       background: var(--color-surface, #fff); color: var(--color-text, #111); }
    |.upload { max-width: 40rem; margin: 0 auto; }
    |.upload h1 { font-size: 1.25rem; margin: 0 0 .5rem; }
    |.upload p { margin: 0 0 1.5rem; opacity: .75; font-size: .9rem; }
    |button { font: inherit; padding: .75rem 1.5rem; border: 0; border-radius: var(--radius-md, .5rem);
    |         background: var(--color-primary, #2563eb); color: var(--text-inverse, #fff); cursor: pointer; }
    |button[disabled] { opacity: .6; cursor: default; }
    |.track { height: 4px; margin-top: .5rem; border-radius: 2px;
    |         background: var(--color-border, #e5e7eb); overflow: hidden; }
    |.track > div { height: 100%; width: 0; background: var(--color-primary, #2563eb); transition: width .2s; }
    |""".stripMargin

  private val rowCss =
    """
    |.row { padding: 1rem 0; border-top: 1px solid var(--color-border, #e5e7eb); }
    |.row .meta { display: flex; justify-content: space-between; margin-bottom: .5rem; }
    |.slug { opacity: .6; font-size: .8rem; }
    |.row .status { margin-top: .5rem; font-size: .8rem; min-height: 1rem; }
    |.row.hidden { display: none; }
    |input[type=file] { display: none; }
    |""".stripMargin

  /** The page, carrying no data at all.
    *
    * It used to be rendered per video, and then rendered with every pending video embedded and the
    * others hidden in JavaScript — which meant a page opened for one video shipped valid signed PUT
    * URLs for the rest. Hiding a row is not withholding it.
    *
    * So the markup is now static and the page fetches: `ui/notifications/tool-result` tells it
    * which video the opening call produced, and `resources/read` on that video's upload resource
    * returns the one signed URL it needs. Reading a resource takes no user consent, unlike a tool
    * call, so this costs the operator nothing.
    *
    * That also disposes of expiry. A signature is arithmetic over the key and the clock, not an
    * allocation, so there is nothing to release and nothing to notify about — the page simply reads
    * again, on a timer before it lapses and on a 403 after. The old design could not do that
    * because its URLs arrived baked into the HTML.
    */
  /** The page, rendered whole.
    *
    * There is nothing dynamic left to build: one video, one row, and the server knows its title and
    * location when the page is addressed per video. So the markup is markup again, and the module
    * on the other side holds only behaviour — the signing, the PUT, and the two messages the host
    * needs to hear.
    *
    * Addressed bare — all `_meta.ui.resourceUri` can name, being built before any call has produced
    * a video — the title and location are blank and the row is hidden until the host says which
    * video this is.
    */
  def app(video: Option[PendingVideo] = None): String =
    "<!doctype html>" +
      html(
        head(
          meta(charset := "utf-8"),
          titleTag("Subir video"),
          styleTag(raw(baseCss)),
          styleTag(raw(rowCss)),
          // Filled from the video's own resource, so a page opened for one location is themed as
          // that location and never as another's.
          styleTag(id := "location-css")
        ),
        body(
          div(
            cls := "upload",
            h1("Subir video"),
            p(
              id := "intro",
              video.fold(
                "Abrí esta página desde create_advertiser_video, o elegí un video pendiente en la lista de recursos.")(
                _ => "El video queda fuera de rotación hasta que el archivo llegue.")
            ),
            div(
              id  := "row",
              cls := (
                if video.isDefined then
                  "row"
                else
                  "row hidden"
              ),
              video.map(v => data.video := v.videoId).getOrElse(cls := "row hidden"),
              div(
                cls := "meta",
                strong(id := "title", video.fold("")(_.title)),
                span(cls  := "slug", id := "slug", video.fold("")(_.slug))),
              button(id := "pick", "Subir archivo"),
              // Any video: the type is whatever the operator picks, and confirm_video_upload
              // checks what actually arrived rather than trusting a declaration made earlier.
              input(id := "file", `type` := "file", accept := "video/*"),
              div(id   := "status"),
              div(cls  := "track", div(id := "bar"))
            )
          ),
          script(raw(appScript))
        )
      ).render
  end app

  /** What the server knows about a video before the page is opened. The signed URL is deliberately
    * absent: the page reads it when it needs it, so it can read it again when it lapses.
    */
  final case class PendingVideo(videoId: String, title: String, slug: String)

  /** Escapes for a JS string literal. The values are server-derived, but a file name reaching this
    * unescaped would be an injection into our own page.
    */
  private def quote(value: String): String =
    val escaped = value
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("<", "\\u003c")
      .replace("\n", "\\n")
    s""""$escaped""""
end UploadUi
