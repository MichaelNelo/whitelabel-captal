package whitelabel.captal.mcp

import com.jamesward.ziohttp.mcp.*
import io.getquill.*
import whitelabel.captal.infra.LocationRow
import whitelabel.captal.infra.schema.QuillSqlite
import whitelabel.captal.infra.schema.given
import zio.*
import zio.schema.annotation.description
import zio.schema.{DeriveSchema, Schema}

import McpSchema.given

/** Per-location overrides of the two assets a location can replace: its stylesheet and its brand
  * icon.
  *
  * Both stay on S3 rather than moving into the database so the portal's render path is untouched
  * and they stay edge-cached. The write invalidates the one object it touched, which is what turns
  * a rebrand from a deploy into a call.
  */
object AssetTools:
  final case class GetIn(slug: String)
  final case class GetStylesOut(slug: String, present: Boolean, css: Option[String])
  final case class GetIconOut(slug: String, present: Boolean, svg: Option[String])

  final case class SetStylesIn(
      slug: String,
      @description(
        "The full stylesheet. Replaces whatever is there — this is not a patch. Read skill://style-reference/SKILL.md for the variables and class hooks worth overriding.")
      css: String)
  final case class SetIconIn(
      slug: String,
      @description(
        "The full SVG document. Replaces whatever is there. It is rendered through an img element on every screen, so it should look right at small sizes and carry no scripting.")
      svg: String)
  final case class SetOut(slug: String, storedBytes: Int, invalidationId: String)

  given Schema[GetIn] = DeriveSchema.gen
  given Schema[GetStylesOut] = DeriveSchema.gen
  given Schema[GetIconOut] = DeriveSchema.gen
  given Schema[SetStylesIn] = DeriveSchema.gen
  given Schema[SetIconIn] = DeriveSchema.gen
  given Schema[SetOut] = DeriveSchema.gen

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

  private def write(
      slug: String,
      asset: Asset,
      content: String): ZIO[QuillSqlite & AssetStore, String, SetOut] =
    for
      _            <- requireLocation(slug)
      store        <- ZIO.service[AssetStore]
      bytes        <- store.put(slug, asset, content)
      invalidation <- store.invalidate(slug, asset)
    yield SetOut(slug, bytes, invalidation)

  /** An SVG reaching the browser only through `img` cannot run scripts — but the object is served
    * from the portal's own origin, so anything that navigates straight to it renders the file as a
    * document, where they do run. That is the whole of the classic SVG upload problem, and the
    * cheapest place to close it is before the write.
    *
    * Deliberately a refusal rather than a sanitiser: stripping tags from markup nobody asked for
    * invites the question of what else was silently changed.
    */
  private[mcp] def validateSvg(svg: String): ZIO[Any, String, Unit] =
    val trimmed = svg.trim
    val lowered = trimmed.toLowerCase
    for
      _ <- ZIO.when(trimmed.isEmpty)(ZIO.fail("The icon is empty."))
      _ <-
        ZIO.when(!lowered.startsWith("<svg") && !lowered.startsWith("<?xml"))(
          ZIO.fail(
            "That does not look like an SVG document: it starts with neither <svg nor <?xml."))
      _ <- ZIO.when(!lowered.contains("<svg"))(ZIO.fail("No <svg> element found."))
      _ <-
        ZIO.when(lowered.contains("<script"))(
          ZIO.fail(
            "The SVG contains a script element. Served from the portal's own origin, it would run for anyone opening the file directly."))
      // Any on* attribute. Matched loosely on purpose: the point is to refuse, not to enumerate.
      _ <-
        ZIO.when("(?s)<[^>]*\\son[a-z]+\\s*=".r.findFirstIn(lowered).isDefined)(
          ZIO.fail("The SVG carries an event handler attribute. Remove it and try again."))
    yield ()

  val getStyles: McpToolHandlerR[QuillSqlite & AssetStore] = McpTool("get_location_styles")
    .description(
      "The location's custom stylesheet, or nothing when it has none and is using the base theme.")
    .handle: (in: GetIn) =>
      for
        _     <- requireLocation(in.slug)
        store <- ZIO.service[AssetStore]
        css   <- store.get(in.slug, Asset.Styles)
      yield GetStylesOut(in.slug, css.isDefined, css)

  val setStyles: McpToolHandlerR[QuillSqlite & AssetStore] = McpTool("set_location_styles")
    .description(
      "Replace the location's custom stylesheet and invalidate the CDN so the change is visible immediately. Sends the whole file, not a patch — call get_location_styles first when editing rather than rewriting.")
    .requireScopes(Auth.ContentWrite)
    .handle: (in: SetStylesIn) =>
      for
        // An empty string would store an empty stylesheet, which looks identical to having none
        // while still costing a request. Removing the override is a different operation.
        _ <-
          ZIO.when(in.css.isBlank)(
            ZIO.fail("The stylesheet is empty. To go back to the base theme, say so explicitly."))
        out <- write(in.slug, Asset.Styles, in.css)
      yield out

  val getIcon: McpToolHandlerR[QuillSqlite & AssetStore] = McpTool("get_location_icon")
    .description(
      "The location's brand icon as SVG source, or nothing when it has none and is using the default.")
    .handle: (in: GetIn) =>
      for
        _     <- requireLocation(in.slug)
        store <- ZIO.service[AssetStore]
        svg   <- store.get(in.slug, Asset.BrandIcon)
      yield GetIconOut(in.slug, svg.isDefined, svg)

  val setIcon: McpToolHandlerR[QuillSqlite & AssetStore] = McpTool("set_location_icon")
    .description(
      "Replace the location's brand icon and invalidate the CDN. The icon appears on every screen, including the loader shown before anything else renders, so a broken one is visible everywhere at once.")
    .requireScopes(Auth.ContentWrite)
    .handle: (in: SetIconIn) =>
      for
        _   <- validateSvg(in.svg)
        out <- write(in.slug, Asset.BrandIcon, in.svg)
      yield out

  val all: List[McpToolHandlerR[QuillSqlite & AssetStore]] = List(
    getStyles,
    setStyles,
    getIcon,
    setIcon)
end AssetTools
