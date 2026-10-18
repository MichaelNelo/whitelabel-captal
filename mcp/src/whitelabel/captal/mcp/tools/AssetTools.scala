package whitelabel.captal.mcp.tools

import com.jamesward.ziohttp.mcp.*
import io.getquill.*
import whitelabel.captal.mcp.access.Auth
import whitelabel.captal.mcp.aws.{Asset, AssetStore}
import whitelabel.captal.mcp.protocol.{CssSchema, McpSchema, ToolSchema}
import whitelabel.captal.platform.LocationRow
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import zio.*
import zio.schema.annotation.description
import zio.schema.{DeriveSchema, Schema}

import McpSchema.given
import ToolSchema.readOnly

/** Per-location overrides of the two assets a location can replace: its stylesheet and its brand
  * icon.
  *
  * Both stay on S3 rather than moving into the database so the portal's render path is untouched
  * and they stay edge-cached. The write invalidates the one object it touched, which is what turns
  * a rebrand from a deploy into a call.
  */
object AssetTools:
  final case class GetRequest(
      @description("Which location's asset to read.")
      slug: String)
  final case class GetStylesResponse(
      @description("The location read.")
      slug: String,
      @description(
        "False when this location has no stylesheet of its own and is running on the bundle's defaults.")
      present: Boolean,
      @description("The stored stylesheet, ungzipped. Absent when there is none.")
      css: Option[String])
  final case class GetIconResponse(
      @description("The location read.")
      slug: String,
      @description("False when this location is showing the bundle's default icon.")
      present: Boolean,
      @description("The stored SVG document. Absent when there is none.")
      svg: Option[String])

  final case class CssSchemaRequest()

  final case class CssVariable(
      @description("The custom property, as written — '--color-primary'.")
      name: String,
      @description("What the portal uses when a location overrides nothing.")
      default: String,
      @description(
        "What it controls. Empty for a variable the stylesheet declares and nobody has documented yet.")
      purpose: String)

  final case class CssGroup(
      @description("The section it belongs to, taken from the stylesheet's own headings.")
      group: String,
      @description("Its variables, in the order the stylesheet declares them.")
      variables: List[CssVariable])

  final case class CssSchemaResponse(
      @description(
        "Every section of the stylesheet's `:root` block. A location overrides what it wants on its own `:root`; everything it leaves out keeps the default.")
      groups: List[CssGroup],
      @description("How many variables there are in total.")
      total: Int)

  final case class SetStylesRequest(
      @description("Which location to restyle. Only this one changes.")
      slug: String,
      @description(
        "The full stylesheet. Replaces whatever is there — this is not a patch. Call get_css_schema for the variables and read_skill('style-reference') for how to use them.")
      css: String)
  final case class SetIconRequest(
      @description("Which location's icon to replace. Only this one changes.")
      slug: String,
      @description(
        "The full SVG document. Replaces whatever is there. It is rendered through an img element on every screen, so it should look right at small sizes and carry no scripting.")
      svg: String)
  final case class SetResponse(
      @description("The location written to.")
      slug: String,
      @description("Size of the stored object, after gzip where it applies.")
      storedBytes: Int,
      @description(
        "The CloudFront invalidation created, so the change is visible rather than waiting behind the edge cache.")
      invalidationId: String)

  given Schema[CssSchemaRequest] = DeriveSchema.gen
  given Schema[CssVariable] = DeriveSchema.gen
  given Schema[CssGroup] = DeriveSchema.gen
  given Schema[CssSchemaResponse] = DeriveSchema.gen
  given Schema[GetRequest] = DeriveSchema.gen
  given Schema[GetStylesResponse] = DeriveSchema.gen
  given Schema[GetIconResponse] = DeriveSchema.gen
  given Schema[SetStylesRequest] = DeriveSchema.gen
  given Schema[SetIconRequest] = DeriveSchema.gen
  given Schema[SetResponse] = DeriveSchema.gen

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
      content: String): ZIO[QuillSqlite & AssetStore, String, SetResponse] =
    for
      _            <- requireLocation(slug)
      store        <- ZIO.service[AssetStore]
      bytes        <- store.put(slug, asset, content)
      invalidation <- store.invalidate(slug, asset)
    yield SetResponse(slug, bytes, invalidation)

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

  /** What there is to theme, as opposed to how. The skill is the how.
    *
    * Names, defaults and grouping come from the portal's own stylesheet rather than a list written
    * here, so a variable that is renamed or dropped cannot keep being recommended. See
    * [[CssSchema]] — this tool is the door, the derivation is there.
    */
  val cssSchema: McpToolHandlerR[Any] = McpTool("get_css_schema")
    .description(
      "Every CSS custom property the portal honours, with its default and what it controls, grouped as the stylesheet groups them. Read this before writing a stylesheet with set_location_styles: a location overrides these on `:root`, and a property outside the set is simply never read. Derived from the portal's own stylesheet, so it cannot name a variable that no longer exists.")
    .readOnly
    .handle: (_: CssSchemaRequest) =>
      ZIO.succeed(
        CssSchemaResponse(
          groups = CssSchema
            .groups
            .map(g =>
              CssGroup(g.group, g.variables.map(v => CssVariable(v.name, v.default, v.purpose)))),
          total = CssSchema.all.size))

  val getStyles: McpToolHandlerR[QuillSqlite & AssetStore] = McpTool("get_location_styles")
    .description(
      "The location's custom stylesheet, or nothing when it has none and is using the base theme.")
    .readOnly
    .handle: (in: GetRequest) =>
      for
        _     <- requireLocation(in.slug)
        store <- ZIO.service[AssetStore]
        css   <- store.get(in.slug, Asset.Styles)
      yield GetStylesResponse(in.slug, css.isDefined, css)

  val setStyles: McpToolHandlerR[QuillSqlite & AssetStore] = McpTool("set_location_styles")
    .description(
      "Replace the location's custom stylesheet and invalidate the CDN so the change is visible immediately. Sends the whole file, not a patch — call get_location_styles first when editing rather than rewriting.")
    .requireScopes(Auth.ContentWrite)
    .handle: (in: SetStylesRequest) =>
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
    .readOnly
    .handle: (in: GetRequest) =>
      for
        _     <- requireLocation(in.slug)
        store <- ZIO.service[AssetStore]
        svg   <- store.get(in.slug, Asset.BrandIcon)
      yield GetIconResponse(in.slug, svg.isDefined, svg)

  val setIcon: McpToolHandlerR[QuillSqlite & AssetStore] = McpTool("set_location_icon")
    .description(
      "Replace the location's brand icon and invalidate the CDN. The icon appears on every screen, including the loader shown before anything else renders, so a broken one is visible everywhere at once.")
    .requireScopes(Auth.ContentWrite)
    .handle: (in: SetIconRequest) =>
      for
        _   <- validateSvg(in.svg)
        out <- write(in.slug, Asset.BrandIcon, in.svg)
      yield out

  val all: List[McpToolHandlerR[QuillSqlite & AssetStore]] = List(
    cssSchema,
    getStyles,
    setStyles,
    getIcon,
    setIcon)
end AssetTools
