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

  /** Deliberately not a framework. One button, one input, one fetch — anything more would be a
    * second frontend to keep working, for a page that uploads a file.
    */
  private val uploadScript =
    """
    |const data = window.__UPLOAD__;
    |const input = document.getElementById('file');
    |const button = document.getElementById('pick');
    |const status = document.getElementById('status');
    |const bar = document.getElementById('bar');
    |
    |button.addEventListener('click', () => input.click());
    |
    |input.addEventListener('change', () => {
    |  const file = input.files[0];
    |  if (!file) return;
    |  button.disabled = true;
    |  status.textContent = 'Subiendo ' + file.name + '…';
    |
    |  const request = new XMLHttpRequest();
    |  request.open('PUT', data.uploadUrl, true);
    |  request.setRequestHeader('Content-Type', data.contentType);
    |
    |  request.upload.addEventListener('progress', (event) => {
    |    if (!event.lengthComputable) return;
    |    bar.style.width = Math.round((event.loaded / event.total) * 100) + '%';
    |  });
    |
    |  request.addEventListener('load', () => {
    |    if (request.status >= 200 && request.status < 300) {
    |      status.textContent = 'Listo. Confirmando…';
    |      // The upload is only half of it: the video stays out of rotation until the server has
    |      // seen the object, so the page has to ask for that itself.
    |      window.parent.postMessage({
    |        type: 'tool',
    |        name: 'confirm_video_upload',
    |        arguments: { videoId: data.videoId }
    |      }, '*');
    |      bar.style.width = '100%';
    |    } else {
    |      button.disabled = false;
    |      status.textContent = 'S3 rechazó la subida (' + request.status + '). Probá de nuevo.';
    |    }
    |  });
    |
    |  request.addEventListener('error', () => {
    |    button.disabled = false;
    |    status.textContent = 'No se pudo conectar con S3. Puede que el enlace haya expirado.';
    |  });
    |
    |  request.send(file);
    |});
    |""".stripMargin

  /** The page's own styling, kept minimal so the location's stylesheet decides how it looks. The
    * variables are the ones `style-reference` documents, so a location that themed the portal
    * themes this too without knowing it exists.
    */
  private val baseCss =
    """
    |body { margin: 0; padding: 2rem; font-family: var(--font-body, system-ui, sans-serif);
    |       background: var(--color-surface, #fff); color: var(--color-text, #111); }
    |.upload { max-width: 32rem; margin: 0 auto; text-align: center; }
    |.upload h1 { font-size: 1.25rem; margin: 0 0 .5rem; }
    |.upload p { margin: 0 0 1.5rem; opacity: .75; font-size: .9rem; }
    |button { font: inherit; padding: .75rem 1.5rem; border: 0; border-radius: var(--radius-md, .5rem);
    |         background: var(--color-primary, #2563eb); color: var(--text-inverse, #fff); cursor: pointer; }
    |button[disabled] { opacity: .6; cursor: default; }
    |.track { height: 4px; margin-top: 1.5rem; border-radius: 2px;
    |         background: var(--color-border, #e5e7eb); overflow: hidden; }
    |.track > div { height: 100%; width: 0; background: var(--color-primary, #2563eb); transition: width .2s; }
    |#status { margin-top: 1rem; font-size: .875rem; min-height: 1.25rem; }
    |input[type=file] { display: none; }
    |""".stripMargin

  def render(
      videoId: String,
      uploadUrl: String,
      contentType: String,
      locationCss: Option[String]): String =
    val payload =
      s"""{"videoId":${quote(videoId)},"uploadUrl":${quote(uploadUrl)},"contentType":${quote(
          contentType)}}"""
    "<!doctype html>" +
      html(
        head(
          meta(charset := "utf-8"),
          titleTag("Subir video"),
          styleTag(raw(baseCss)),
          // After the base so a themed location overrides it, the same order the portal uses.
          locationCss.map(css => styleTag(raw(css))).getOrElse(frag())
        ),
        body(
          div(
            cls := "upload",
            h1("Subir video"),
            p("El video queda fuera de rotación hasta que el archivo llegue."),
            button(id := "pick", "Subir video"),
            input(id  := "file", `type` := "file", accept := contentType),
            div(id    := "status"),
            div(cls   := "track", div(id := "bar"))
          ),
          script(raw(s"window.__UPLOAD__ = $payload;")),
          script(raw(uploadScript))
        )
      ).render
  end render

  /** One row per video still waiting for its file.
    *
    * `_meta.ui.resourceUri` is declared on the tool, before any call has produced a video, and
    * `CallToolResult` carries no `_meta` to correct it afterwards — so the URI a host reads cannot
    * name a particular video. Pointing it at a template and hoping the host substitutes was the
    * original mistake: it read `ui://upload-video/{videoId}` literally and found nothing.
    *
    * So the page is addressed by what it is rather than by which video: everything awaiting an
    * upload. The one the operator just created is in the list, and so is anything abandoned
    * earlier, which is the more useful thing to be shown anyway.
    */
  def renderPending(items: List[Pending], locationCss: Option[String]): String =
    val payload = items
      .map(i =>
        s"""{"videoId":${quote(i.videoId)},"uploadUrl":${quote(i.uploadUrl)},"contentType":${quote(
            i.contentType)}}""")
      .mkString("[", ",", "]")
    "<!doctype html>" +
      html(
        head(
          meta(charset := "utf-8"),
          titleTag("Subir videos"),
          styleTag(raw(baseCss)),
          styleTag(raw(pendingCss)),
          locationCss.map(css => styleTag(raw(css))).getOrElse(frag())
        ),
        body(
          div(
            cls := "upload",
            h1("Subir videos"),
            if items.isEmpty then
              p("No hay videos esperando archivo. Creá uno con create_advertiser_video.")
            else
              p("Cada video queda fuera de rotación hasta que su archivo llegue.")
            ,
            items.map(item =>
              div(
                cls := "row",
                div(cls    := "meta", strong(item.title), span(cls := "slug", item.slug)),
                button(cls := "pick", data.video := item.videoId, "Subir archivo"),
                input(
                  cls        := "file",
                  data.video := item.videoId,
                  `type`     := "file",
                  accept     := item.contentType),
                div(cls      := "status", data.video := item.videoId),
                div(cls      := "track", div(cls := "bar", data.video := item.videoId))
              ))
          ),
          script(raw(s"window.__UPLOADS__ = $payload;")),
          script(raw(pendingScript))
        )
      ).render
  end renderPending

  final case class Pending(
      videoId: String,
      title: String,
      slug: String,
      uploadUrl: String,
      contentType: String)

  private val pendingCss =
    """
    |.upload { max-width: 40rem; text-align: left; }
    |.row { padding: 1rem 0; border-top: 1px solid var(--color-border, #e5e7eb); }
    |.row .meta { display: flex; justify-content: space-between; margin-bottom: .5rem; }
    |.slug { opacity: .6; font-size: .8rem; }
    |.row .status { margin-top: .5rem; font-size: .8rem; min-height: 1rem; }
    |.row .track { margin-top: .5rem; }
    |input[type=file].file { display: none; }
    |""".stripMargin

  /** The same upload as the single-video page, once per row. Kept as one listener over the list
    * rather than a closure per item so the page stays readable at any number of pending videos.
    */
  private val pendingScript =
    """
    |const uploads = {};
    |for (const u of window.__UPLOADS__) uploads[u.videoId] = u;
    |const pick = (sel, id) => document.querySelector(sel + '[data-video="' + id + '"]');
    |
    |for (const button of document.querySelectorAll('button.pick')) {
    |  const id = button.dataset.video;
    |  const input = pick('input.file', id);
    |  button.addEventListener('click', () => input.click());
    |
    |  input.addEventListener('change', () => {
    |    const file = input.files[0];
    |    if (!file) return;
    |    const data = uploads[id];
    |    const status = pick('div.status', id);
    |    const bar = pick('div.bar', id);
    |    button.disabled = true;
    |    status.textContent = 'Subiendo ' + file.name + '…';
    |
    |    const request = new XMLHttpRequest();
    |    request.open('PUT', data.uploadUrl, true);
    |    request.setRequestHeader('Content-Type', data.contentType);
    |
    |    request.upload.addEventListener('progress', (event) => {
    |      if (!event.lengthComputable) return;
    |      bar.style.width = Math.round((event.loaded / event.total) * 100) + '%';
    |    });
    |
    |    request.addEventListener('load', () => {
    |      if (request.status >= 200 && request.status < 300) {
    |        status.textContent = 'Listo. Confirmando…';
    |        bar.style.width = '100%';
    |        // The video stays out of rotation until the server has seen the object, so the page
    |        // has to ask for that itself.
    |        window.parent.postMessage({
    |          type: 'tool',
    |          name: 'confirm_video_upload',
    |          arguments: { videoId: id }
    |        }, '*');
    |      } else {
    |        button.disabled = false;
    |        status.textContent = 'S3 rechazó la subida (' + request.status + ').';
    |      }
    |    });
    |
    |    request.addEventListener('error', () => {
    |      button.disabled = false;
    |      status.textContent = 'No se pudo conectar con S3. Puede que el enlace haya expirado.';
    |    });
    |
    |    request.send(file);
    |  });
    |}
    |""".stripMargin

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
