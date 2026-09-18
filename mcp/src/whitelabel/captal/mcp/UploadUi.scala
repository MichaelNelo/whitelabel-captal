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

  /** Deliberately not a framework. One fetch, one input, one PUT — anything more would be a second
    * frontend to keep working, for a page that uploads a file.
    */
  private val appScript =
    """
    |let nextId = 1;
    |const waiting = new Map();
    |
    |const call = (method, params) => new Promise((resolve, reject) => {
    |  const id = nextId++;
    |  waiting.set(id, { resolve, reject });
    |  window.parent.postMessage({ jsonrpc: '2.0', id, method, params }, '*');
    |});
    |const notify = (method, params) =>
    |  window.parent.postMessage({ jsonrpc: '2.0', method, params }, '*');
    |
    |window.addEventListener('message', (event) => {
    |  const message = event.data;
    |  if (!message || message.jsonrpc !== '2.0') return;
    |  if (message.id !== undefined && waiting.has(message.id)) {
    |    const { resolve, reject } = waiting.get(message.id);
    |    waiting.delete(message.id);
    |    message.error ? reject(message.error) : resolve(message.result);
    |    return;
    |  }
    |  // The result of the call that opened this page. The URI cannot carry the video id — it is
    |  // declared before any call has produced one — so the host hands it over here instead.
    |  if (message.method === 'ui/notifications/tool-result') {
    |    const structured = (message.params || {}).structuredContent || {};
    |    if (structured.videoId) showOne(structured.videoId);
    |  }
    |});
    |
    |const readJson = async (uri) => {
    |  const result = await call('resources/read', { uri });
    |  return JSON.parse(result.contents[0].text);
    |};
    |
    |const el = (tag, attrs, text) => {
    |  const node = document.createElement(tag);
    |  for (const k in attrs) node.setAttribute(k, attrs[k]);
    |  if (text !== undefined) node.textContent = text;
    |  return node;
    |};
    |
    |async function showOne(videoId) {
    |  const rows = document.getElementById('rows');
    |  rows.innerHTML = '';
    |  document.getElementById('intro').textContent =
    |    'El video queda fuera de rotación hasta que el archivo llegue.';
    |  rows.appendChild(await rowFor(videoId));
    |}
    |
    |async function rowFor(videoId) {
    |  let ticket = await readJson('captal://videos/' + videoId + '/upload');
    |  if (ticket.styles) document.getElementById('location-css').textContent = ticket.styles;
    |
    |  const row = el('div', { class: 'row' });
    |  const meta = el('div', { class: 'meta' });
    |  meta.appendChild(el('strong', {}, ticket.title));
    |  meta.appendChild(el('span', { class: 'slug' }, ticket.slug));
    |  const button = el('button', {}, 'Subir archivo');
    |  const input = el('input', { type: 'file', accept: ticket.contentType });
    |  input.style.display = 'none';
    |  const status = el('div', { class: 'status' });
    |  const track = el('div', { class: 'track' });
    |  const bar = el('div', {});
    |  track.appendChild(bar);
    |  row.append(meta, button, input, status, track);
    |
    |  // Re-read before the signature lapses rather than letting the operator discover it at the
    |  // PUT. Reading costs nothing: a signature is computed, not allocated.
    |  const refresh = async () => { ticket = await readJson('captal://videos/' + videoId + '/upload'); };
    |  setInterval(refresh, Math.max(60, ticket.expiresInSeconds - 300) * 1000);
    |
    |  button.addEventListener('click', () => input.click());
    |  input.addEventListener('change', async () => {
    |    const file = input.files[0];
    |    if (!file) return;
    |    button.disabled = true;
    |    status.textContent = 'Subiendo ' + file.name + '…';
    |
    |    const put = (url) => new Promise((resolve) => {
    |      const request = new XMLHttpRequest();
    |      request.open('PUT', url, true);
    |      request.setRequestHeader('Content-Type', ticket.contentType);
    |      request.upload.addEventListener('progress', (event) => {
    |        if (event.lengthComputable) {
    |          bar.style.width = Math.round((event.loaded / event.total) * 100) + '%';
    |        }
    |      });
    |      request.addEventListener('load', () => resolve(request.status));
    |      request.addEventListener('error', () => resolve(0));
    |      request.send(file);
    |    });
    |
    |    let code = await put(ticket.uploadUrl);
    |    // A 403 is what an expired signature looks like. One re-read and one retry, rather than
    |    // telling the operator to reopen the page.
    |    if (code === 403) {
    |      status.textContent = 'El enlace había expirado; reintentando…';
    |      await refresh();
    |      code = await put(ticket.uploadUrl);
    |    }
    |
    |    if (code >= 200 && code < 300) {
    |      status.textContent = 'Listo. Confirmando…';
    |      bar.style.width = '100%';
    |      await call('tools/call', {
    |        name: 'confirm_video_upload',
    |        arguments: { videoId }
    |      });
    |      status.textContent = 'Confirmado. El video está en rotación.';
    |      // A call the page makes is invisible to the model, so the agent would keep waiting on
    |      // something already done. This is what puts it in the conversation.
    |      notify('ui/message', {
    |        role: 'user',
    |        content: { type: 'text', text: 'Subí el archivo del video ' + videoId + ' y lo confirmé.' }
    |      });
    |    } else {
    |      button.disabled = false;
    |      status.textContent = code === 0
    |        ? 'No se pudo conectar con S3.'
    |        : 'S3 rechazó la subida (' + code + ').';
    |    }
    |  });
    |  return row;
    |}
    |
    |async function start() {
    |  await call('ui/initialize', {
    |    appCapabilities: { availableDisplayModes: ['inline', 'fullscreen'] }
    |  });
    |  notify('ui/notifications/initialized', {});
    |
    |  // Opened on its own rather than by a call: show everything outstanding, still one signed URL
    |  // per row and none of them in the markup.
    |  const pending = await readJson('captal://videos/pending');
    |  const rows = document.getElementById('rows');
    |  const intro = document.getElementById('intro');
    |  if (pending.videos.length === 0) {
    |    intro.textContent = 'No hay videos esperando archivo. Creá uno con create_advertiser_video.';
    |    return;
    |  }
    |  intro.textContent = 'Cada video queda fuera de rotación hasta que su archivo llegue.';
    |  for (const v of pending.videos) rows.appendChild(await rowFor(v.videoId));
    |}
    |
    |start();
    |""".stripMargin

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

  private val pendingCss =
    """
    |.row { padding: 1rem 0; border-top: 1px solid var(--color-border, #e5e7eb); }
    |.row .meta { display: flex; justify-content: space-between; margin-bottom: .5rem; }
    |.slug { opacity: .6; font-size: .8rem; }
    |.row .status { margin-top: .5rem; font-size: .8rem; min-height: 1rem; }
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
  def app(): String =
    "<!doctype html>" +
      html(
        head(
          meta(charset := "utf-8"),
          titleTag("Subir video"),
          styleTag(raw(baseCss)),
          styleTag(raw(pendingCss)),
          // Filled from the video's own resource, so a page opened for one location is themed as
          // that location and never as another's.
          styleTag(id := "location-css")
        ),
        body(
          div(cls := "upload", h1("Subir video"), p(id := "intro", "Cargando…"), div(id := "rows")),
          script(raw(appScript)))
      ).render

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
