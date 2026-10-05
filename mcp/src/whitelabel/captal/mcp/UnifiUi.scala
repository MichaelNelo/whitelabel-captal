package whitelabel.captal.mcp

import com.jamesward.ziohttp.mcp.McpUiUri
import scalatags.Text.all.*
import scalatags.Text.tags2.{style as styleTag, title as titleTag}

/** The UniFi configuration page, rendered server-side.
  *
  * An MCP App: `configure_unifi` points at `ui://configure-unifi` through `_meta.ui`, the host
  * reads that resource and renders it in a sandboxed iframe, and the page posts the credentials
  * straight to [[UiRoutes]]. The token never passes through this server as a tool argument, which
  * is the whole reason the page exists — every `tools/call` is recorded by the host and replayed
  * into the model's context on every later turn, and an API token that authorises access on someone
  * else's network does not belong there.
  *
  * A page rather than a link to open in a browser, for two reasons that only showed up when the
  * alternative was written out. The operator never leaves the agent; and the agent is told the
  * result over `postMessage`, which a browser tab has no way to report. A form the operator filled
  * in somewhere else would leave the model guessing whether it happened.
  *
  * Carries no data. `_meta.ui.resourceUri` is fixed when the tool is defined, long before any slug
  * is known, so the page is addressed bare and everything it needs — the ticket, the location, what
  * is already stored — arrives with the result of the call that opened it. Until then the form is
  * hidden rather than empty: a form that renders before it knows which location it edits invites
  * exactly one mistake.
  */
object UnifiUi:
  val Uri: String = "ui://configure-unifi"

  /** The same URI as the SDK's type, for the places that take one. */
  val AppUri: McpUiUri = ToolSchema.uiUri(Uri)

  /** The page's behaviour, linked from the `mcpui-unifi` Scala.js module and inlined here.
    *
    * Inlined rather than served, because the iframe's CSP blocks every external host. A module
    * rather than a string literal, for the reason [[UploadUi]] records: the two bugs that cost that
    * migration a session were a `postMessage` envelope no host reads and a missing `ui/message`,
    * and a string literal cannot be wrong about those loudly.
    */
  private lazy val appScript: String =
    val stream = getClass.getResourceAsStream("/unifi-app.js")
    if stream == null then
      throw new IllegalStateException(
        "unifi-app.js is missing from the jar. It is produced by `mcpui-unifi.fullLinkJS` and copied in `mcp.resources`.")
    else
      try new String(stream.readAllBytes(), "UTF-8")
      finally stream.close()

  /** Minimal, and themed by whatever surrounds it.
    *
    * Each colour falls back twice: the location's variable from `style-reference`, then the host's
    * from Claude's design guidelines, then a literal. A portal that was themed themes this page;
    * one that was not inherits the conversation's own palette instead of a hardcoded white.
    *
    * This form in particular has no location stylesheet to fall back on in practice — it is opened
    * before a location is finished, often before it has any branding at all — so the host's tokens
    * are what it actually renders with.
    */
  private val css =
    """
    |/* Unpainted: the frames between this page and the conversation are already transparent, and
    |   anything opaque here hides the chat surface behind it. */
    |html, body { background: var(--color-surface, transparent); }
    |body { margin: 0; padding: 2rem;
    |       font-family: var(--font-body, var(--font-sans, system-ui, sans-serif));
    |       font-size: var(--font-text-md-size, 16px);
    |       color: var(--color-text, var(--color-text-primary, #111)); }
    |.unifi { max-width: 38rem; margin: 0 auto; }
    |h1 { font-size: var(--font-heading-md-size, 1.25rem); margin: 0 0 .25rem; }
    |#slug { font-size: var(--font-text-xs-size, .8rem); color: var(--color-text-tertiary, inherit); opacity: .6; }
    |#lede { margin: .75rem 0 1.5rem; font-size: var(--font-text-sm-size, .9rem); line-height: 1.5;
    |        color: var(--color-text-secondary, inherit); opacity: .8; }
    |form { display: none; }
    |form.ready { display: block; }
    |label { display: block; margin-bottom: 1rem; font-size: var(--font-text-sm-size, .85rem); }
    |label > span { display: block; margin-bottom: .25rem; font-weight: var(--font-weight-semibold, 600); }
    |label > em { display: block; margin-top: .25rem; font-style: normal;
    |             font-size: var(--font-text-xs-size, .75rem);
    |             color: var(--color-text-tertiary, inherit); opacity: .6; }
    |input { font: inherit; width: 100%; box-sizing: border-box; padding: .5rem .75rem;
    |        border: var(--border-width-regular, 1px) solid
    |                var(--color-border, var(--color-border-primary, #e5e7eb));
    |        border-radius: var(--radius-md, var(--border-radius-md, .375rem));
    |        background: var(--color-surface, var(--color-background-secondary, #fff)); color: inherit; }
    |input:focus-visible { outline: 2px solid var(--color-ring-primary, currentColor); outline-offset: 1px; }
    |.pair { display: flex; gap: 1rem; }
    |.pair > label { flex: 1; }
    |button { font: inherit; padding: .75rem 1.5rem; border: 0;
    |         border-radius: var(--radius-md, var(--border-radius-md, .375rem));
    |         font-weight: var(--font-weight-semibold, 600);
    |         background: var(--color-primary, var(--color-background-inverse, #2563eb));
    |         color: var(--text-inverse, var(--color-text-inverse, #fff)); cursor: pointer; }
    |button[disabled] { opacity: .6; cursor: default; }
    |.status { margin-top: .75rem; font-size: var(--font-text-sm-size, .85rem); min-height: 1.2rem; }
    |.status.bad { color: var(--color-error, var(--color-text-danger, #b91c1c)); }
    |""".stripMargin

  private def field(
      fieldName: String,
      labelText: String,
      hint: String,
      inputType: String = "text") = label(
    span(labelText),
    input(`type` := inputType, id := fieldName, name := fieldName, attr("autocomplete") := "off"),
    em(hint))

  def app(): String =
    "<!doctype html>" +
      html(
        head(
          meta(charset := "utf-8"),
          // Without this the browser paints an opaque canvas behind the frame — white in light
          // mode — whenever this document's colour scheme differs from the host's, before any
          // script has run. Declaring both opts in to whichever the host is using.
          meta(name := "color-scheme", content := "light dark"),
          titleTag("Configurar UniFi"),
          styleTag(raw(css))
        ),
        body(
          div(
            cls := "unifi",
            h1("Configurar UniFi"),
            div(id := "slug"),
            p(id   := "lede", "Abrí esta página desde configure_unifi."),
            form(
              id := "form",
              // `host` and `siteId` are the two `UnifiAccess.fromRow` cannot build without. The
              // token is the third, and the script marks it required only when none is stored.
              field("host", "Host", "Dirección del UCG, sin esquema ni puerto.")(
                required := "required"),
              div(
                cls := "pair",
                field("port", "Puerto", "Vacío usa 443."),
                field("durationMinutes", "Duración (minutos)", "Vacío usa 1440.")),
              field(
                "siteId",
                "Site id",
                "El UUID que devuelve /proxy/network/integration/v1/sites. El nombre heredado (\"default\") no funciona.")(
                required := "required"),
              field(
                "apiToken",
                "API token",
                "Va directo al servidor y no pasa por la conversación. Nunca se muestra de vuelta.",
                inputType = "password"),
              field("apMac", "MAC del AP", "Con qué AP el dispatcher resuelve esta location."),
              field(
                "redirectUrl",
                "URL de redirección",
                "A dónde se manda al invitado tras autorizar. Vacío usa la que trae UniFi."),
              button(id := "save", `type` := "submit", "Guardar"),
              div(id    := "status", cls  := "status")
            )
          ),
          script(raw(appScript))
        )
      ).render
end UnifiUi
