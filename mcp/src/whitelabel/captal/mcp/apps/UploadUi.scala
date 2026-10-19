package whitelabel.captal.mcp.apps

import com.jamesward.ziohttp.mcp.McpUiUri
import scalatags.Text.all.*
import scalatags.Text.tags2.{style as styleTag, title as titleTag}
import whitelabel.captal.mcp.protocol.ToolSchema

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

  /** The same URI as the SDK's type, for the places that take one. Validated here, so a typo stops
    * the server rather than producing a tool nobody can render.
    */
  val AppUri: McpUiUri = ToolSchema.uiUri(Uri)

  /** The entry the page loads, by name. `AssetStore.scriptUrl` turns it into a URL. */
  // `private[mcp]` rather than `private`: the render site needs it too, to ask
  // [[AssetStore.scriptPreloads]] which chunks *this* entry imports. Naming it in two places is
  // what the hint would otherwise cost.
  private[mcp] val EntryScript = "upload.js"

  /** The page's own styling, kept minimal so the surrounding themes decide how it looks.
    *
    * Every colour is a two-step fallback: the location's variable first, then the host's, then a
    * literal. The location's names come from `style-reference`; the host's come from Claude's
    * design guidelines.
    */
  private val baseCss =
    """
    |/* Unpainted on purpose. Every frame the host puts between this page and the conversation is
    |   already transparent, so leaving the background alone is what lets the chat surface show
    |   through -- painting one is what made this a white slab inside a dark conversation. A
    |   location's own stylesheet may still set `--color-surface`, and then it wins. */
    |html, body { background: var(--color-surface, transparent); }
    |body { margin: 0; padding: 2rem;
    |       font-family: var(--font-body, var(--font-sans, system-ui, sans-serif));
    |       font-size: var(--font-text-md-size, 16px);
    |       line-height: var(--font-text-md-line-height, 1.4);
    |       color: var(--color-text, var(--color-text-primary, #111)); }
    |.upload { max-width: 40rem; margin: 0 auto; }
    |.upload h1 { font-size: var(--font-heading-md-size, 1.25rem); margin: 0 0 .5rem; }
    |.upload p { margin: 0 0 1.5rem; font-size: var(--font-text-sm-size, .9rem);
    |            color: var(--color-text-secondary, inherit); opacity: var(--secondary-opacity, .75); }
    |button { font: inherit; padding: .75rem 1.5rem; border: 0;
    |         border-radius: var(--radius-md, var(--border-radius-md, .5rem));
    |         font-weight: var(--font-weight-semibold, 600);
    |         background: var(--color-primary, var(--color-background-inverse, #2563eb));
    |         color: var(--text-inverse, var(--color-text-inverse, #fff)); cursor: pointer; }
    |button[disabled] { opacity: .6; cursor: default; }
    |button:focus-visible { outline: 2px solid var(--color-ring-primary, currentColor); outline-offset: 2px; }
    |.track { height: 4px; margin-top: .5rem; border-radius: 2px;
    |         background: var(--color-border, var(--color-border-tertiary, #e5e7eb)); overflow: hidden; }
    |.track > div { height: 100%; width: 0;
    |               background: var(--color-primary, var(--color-background-inverse, #2563eb));
    |               transition: width .2s; }
    |""".stripMargin

  private val rowCss =
    """
    |.row { padding: 1rem 0;
    |       border-top: var(--border-width-regular, 1px) solid
    |                   var(--color-border, var(--color-border-tertiary, #e5e7eb)); }
    |.row .meta { display: flex; justify-content: space-between; margin-bottom: .5rem; }
    |.slug { font-size: var(--font-text-xs-size, .8rem); color: var(--color-text-tertiary, inherit); opacity: .6; }
    |.row .status { margin-top: .5rem; font-size: var(--font-text-xs-size, .8rem);
    |               color: var(--color-text-secondary, inherit); min-height: 1rem; }
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
  def app(
      scriptBase: String => String,
      video: Option[PendingVideo] = None,
      scriptOrigin: String = "",
      preloads: Seq[String] = Nil): String =
    "<!doctype html>" +
      html(
        head(
          meta(charset := "utf-8"),
          // Two hints about the module at the bottom, buying different things.
          //
          // `preconnect` is about the origin: the script comes from the CDN and this document from
          // the server, so the browser has a DNS lookup and a TCP and TLS handshake to do before it
          // can ask for anything. Named here, that happens while the rest of the head is still
          // being parsed rather than after.
          //
          // `modulepreload` is about the graph: the entry imports chunks whose names only it knows,
          // so without this the browser must fetch and parse the entry before it can ask for them.
          // Naming them here is one round trip off the critical path — 107 ms at 100 ms of RTT, 308
          // at 300. Both are simply absent when the caller has nothing to offer; see
          // [[AssetStore.scriptPreloads]] for why that is the safe direction to fail in.
          Hints(scriptOrigin, preloads),
          // Covers the first paint, before any script runs. A browser gives an iframe document an
          // opaque canvas — white in light mode — whenever its colour scheme differs from the
          // embedding page, and that backdrop sits behind everything regardless of what the CSS
          // says. Declaring both schemes opts this document into whichever one the host is in.
          meta(name := "color-scheme", content := "light dark"),
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
                "Abrí esta página desde create_advertiser_video o create_promo_video. Para retomar una subida a medio camino, pedí resume_video_upload — list_pending_uploads enumera las que esperan archivo.")(
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
          // `type="module"`, which the entry needs to import its chunk — and which also means the
          // browser fetches it in CORS mode, unlike a classic script. `client.publish` sets
          // `Access-Control-Allow-Origin: *` for exactly that reason.
          script(`type` := "module", src := scriptBase(EntryScript))
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
