package whitelabel.captal.mcp.apps

import com.jamesward.ziohttp.mcp.McpUiUri
import scalatags.Text.all.*
import scalatags.Text.tags2.{style as styleTag, title as titleTag}
import whitelabel.captal.mcp.protocol.ToolSchema

/** The page that follows a deploy, pause, resume or destroy, rendered server-side.
  *
  * An MCP App: the four tools point at `ui://deploy-run` through `_meta.ui`, and the run arrives
  * with the result of the call that opened the page — its steps, and a [[RunTicket]] for
  * [[RunRoutes]], which streams the run as it moves. The operator watches every step tick over
  * instead of a conversation that goes quiet for minutes.
  *
  * Carries no data, for the same reason as [[UnifiUi]]: the resource URI is fixed when the tool is
  * defined, before any run exists.
  */
object RunUi:
  val Uri: String = "ui://deploy-run"

  /** The same URI as the SDK's type, for the places that take one. */
  val AppUri: McpUiUri = ToolSchema.uiUri(Uri)

  // `private[mcp]` for the same reason as [[UploadUi.EntryScript]]: the render site asks
  // [[AssetStore.scriptPreloads]] about this exact entry.
  private[mcp] val EntryScript = "run.js"

  /** Themed by whatever surrounds it, with the same double fallback as [[UnifiUi]]. */
  private val css =
    """
    |html, body { background: var(--color-surface, transparent); }
    |body { margin: 0; padding: 1.5rem 2rem;
    |       font-family: var(--font-body, var(--font-sans, system-ui, sans-serif));
    |       font-size: var(--font-text-md-size, 16px);
    |       color: var(--color-text, var(--color-text-primary, #111)); }
    |.run { max-width: 38rem; margin: 0 auto; }
    |h1 { font-size: var(--font-heading-md-size, 1.25rem); margin: 0 0 .25rem; }
    |#slug { font-size: var(--font-text-xs-size, .8rem); color: var(--color-text-tertiary, inherit); opacity: .6; }
    |#lede { margin: .75rem 0 1rem; font-size: var(--font-text-sm-size, .9rem); line-height: 1.5;
    |        color: var(--color-text-secondary, inherit); opacity: .8; }
    |ol { list-style: none; margin: 0; padding: 0; }
    |li { display: flex; gap: .75rem; align-items: baseline; padding: .4rem 0;
    |     font-size: var(--font-text-sm-size, .9rem);
    |     border-bottom: var(--border-width-regular, 1px) solid
    |                    var(--color-border, var(--color-border-primary, #e5e7eb)); }
    |li:last-child { border-bottom: 0; }
    |li::before { flex: none; width: 1.25rem; text-align: center; font-weight: 600; }
    |li.pending { opacity: .5; }
    |li.pending::before { content: "·"; }
    |li.running::before { content: "…"; }
    |li.done::before { content: "✓"; color: var(--color-success, var(--color-text-success, #15803d)); }
    |li.failed::before { content: "✕"; color: var(--color-error, var(--color-text-danger, #b91c1c)); }
    |li.running { font-weight: var(--font-weight-semibold, 600); }
    |li small { display: block; font-weight: normal; opacity: .7;
    |           font-size: var(--font-text-xs-size, .75rem); }
    |.status { margin-top: 1rem; font-size: var(--font-text-sm-size, .85rem); min-height: 1.2rem; }
    |.status.bad { color: var(--color-error, var(--color-text-danger, #b91c1c)); }
    |""".stripMargin

  def app(
      scriptBase: String => String,
      scriptOrigin: String = "",
      preloads: Seq[String] = Nil): String =
    "<!doctype html>" +
      html(
        head(
          meta(charset := "utf-8"),
          Hints(scriptOrigin, preloads),
          // See [[UnifiUi]]: without it the frame paints an opaque canvas before any script runs.
          meta(name := "color-scheme", content := "light dark"),
          titleTag("Progreso"),
          styleTag(raw(css))
        ),
        body(
          div(
            cls := "run",
            h1(id  := "title", "Progreso"),
            div(id := "slug"),
            p(
              id := "lede",
              "Abrí esta página desde deploy_location, pause_location, resume_location o destroy_location."),
            ol(id  := "steps"),
            div(id := "status", cls := "status")
          ),
          // `type="module"` so the fetch is CORS mode, which `client.publish` answers.
          script(`type` := "module", src := scriptBase(EntryScript))
        )
      ).render
end RunUi
