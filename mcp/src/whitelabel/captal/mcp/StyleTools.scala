package whitelabel.captal.mcp

import com.jamesward.ziohttp.mcp.*
import io.getquill.*
import whitelabel.captal.infra.LocationRow
import whitelabel.captal.infra.schema.QuillSqlite
import whitelabel.captal.infra.schema.given
import zio.*
import zio.schema.annotation.description
import zio.schema.{DeriveSchema, Schema}

/** Per-location stylesheet.
  *
  * The SPA loads the base stylesheet and then `./custom-styles.css.gz` from the same prefix, so
  * whatever is written here overrides the defaults by cascade order. A location without one is
  * normal: the `onerror` on that link element swallows the 404 and the portal uses the base theme.
  *
  * Kept on S3 rather than moved into the database so the portal's render path is untouched and the
  * stylesheet stays edge-cached. The cost is that a write has to invalidate CloudFront, which the
  * CLI never did — an operator could push a rebrand and see nothing change until the cache expired.
  */
object StyleTools:
  final case class GetStylesIn(slug: String)
  final case class GetStylesOut(slug: String, present: Boolean, css: Option[String])

  final case class SetStylesIn(
      slug: String,
      @description(
        "The full stylesheet. Replaces whatever is there — this is not a patch. Read skill://style-reference/SKILL.md for the variables and class hooks worth overriding.")
      css: String)
  final case class SetStylesOut(slug: String, compressedBytes: Int, invalidationId: String)

  given Schema[GetStylesIn] = DeriveSchema.gen
  given Schema[GetStylesOut] = DeriveSchema.gen
  given Schema[SetStylesIn] = DeriveSchema.gen
  given Schema[SetStylesOut] = DeriveSchema.gen

  private inline def locationBySlugQuery = quote: (slugParam: String) =>
    query[LocationRow].filter(_.slug == slugParam).take(1)

  /** Verified against the database even though the write goes to S3: a typo'd slug would otherwise
    * create an object under a prefix no portal ever reads, and nothing would report it.
    */
  private def requireLocation(slug: String): ZIO[QuillSqlite, String, Unit] = ZIO.serviceWithZIO[
    QuillSqlite]: quill =>
    import quill.*
    run(locationBySlugQuery(lift(slug)))
      .orDie
      .map(_.headOption)
      .someOrFail(s"No location with slug '$slug'.")
      .unit

  val get: McpToolHandlerR[QuillSqlite & AssetStore] = McpTool("get_location_styles")
    .description(
      "The location's custom stylesheet, or nothing when it has none and is using the base theme.")
    .handle: (in: GetStylesIn) =>
      for
        _     <- requireLocation(in.slug)
        store <- ZIO.service[AssetStore]
        css   <- store.getStyles(in.slug)
      yield GetStylesOut(in.slug, css.isDefined, css)

  val set: McpToolHandlerR[QuillSqlite & AssetStore] = McpTool("set_location_styles")
    .description(
      "Replace the location's custom stylesheet and invalidate the CDN so the change is visible immediately. Sends the whole file, not a patch — call get_location_styles first when editing rather than rewriting.")
    .requireScopes(Auth.ContentWrite)
    .handle: (in: SetStylesIn) =>
      for
        _ <- requireLocation(in.slug)
        // An empty string would store an empty stylesheet, which looks identical to having none
        // while still costing a request. Removing the override is a different operation.
        _ <-
          ZIO.when(in.css.isBlank)(
            ZIO.fail("The stylesheet is empty. To go back to the base theme, say so explicitly."))
        store        <- ZIO.service[AssetStore]
        bytes        <- store.putStyles(in.slug, in.css)
        invalidation <- store.invalidate(in.slug)
      yield SetStylesOut(in.slug, bytes, invalidation)

  val all: List[McpToolHandlerR[QuillSqlite & AssetStore]] = List(get, set)
end StyleTools
