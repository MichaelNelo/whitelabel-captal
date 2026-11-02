package whitelabel.captal.mcp.apps

import com.jamesward.ziohttp.mcp.McpUiUri
import scalatags.Text.all.*
import scalatags.Text.tags2.{style as styleTag, title as titleTag}
import whitelabel.captal.mcp.protocol.ToolSchema

/** The page `preview_location` opens: one screen of the portal with a location's look.
  *
  * The page is the portal itself, `preview.html` as `client.bundle` builds it — the portal's
  * stylesheet and its one-file build inline — read from the bundle as published
  * (`AssetStore.PreviewPageKey`) and served as it is. Like every page here it is fixed when the
  * tool is defined and carries no location: the location's texts, stylesheet, icon and video arrive
  * with the result of the call that opened it.
  */
object PreviewUi:
  val Uri: String = "ui://preview-location"

  /** The same URI as the SDK's type, for the places that take one. */
  val AppUri: McpUiUri = ToolSchema.uiUri(Uri)

  /** What the page is when the bundle has no preview page published: a bundle published before the
    * preview existed, or none at all. Says so rather than rendering blank.
    */
  def unpublished: String =
    "<!doctype html>" +
      html(
        head(
          meta(charset := "utf-8"),
          meta(name    := "color-scheme", content := "light dark"),
          titleTag("Vista previa"),
          styleTag(
            raw(
              """body { margin: 0; padding: 1.5rem 2rem;
                |       font-family: var(--font-sans, system-ui, sans-serif);
                |       color: var(--color-text-primary, #111); background: transparent; }"""
                .stripMargin))
        ),
        body(
          p(
            "El bundle publicado no trae la página de vista previa. Publicá el cliente (",
            code("mill client.publishS3 --bucket <bucket> --prefix bundle"),
            ") y volvé a abrirla."
          ))
      ).render
end PreviewUi
