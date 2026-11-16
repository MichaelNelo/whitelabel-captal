package whitelabel.captal.mcp.tools

import java.time.Instant

import cats.syntax.foldable.*
import com.jamesward.ziohttp.mcp.*
import whitelabel.captal.core.application.commands.{ChangeIconCommand, ChangeStylesCommand}
import whitelabel.captal.core.application.queries.{IconOfQuery, StylesOfQuery}
import whitelabel.captal.core.application.{Error as AppError, Flow}
import whitelabel.captal.core.location.Slug
import whitelabel.captal.mcp.access.Auth
import whitelabel.captal.mcp.protocol.{CssSchema, McpSchema, ToolSchema}
import whitelabel.captal.platform.aws.AssetStore
import whitelabel.captal.platform.queries.PublishedDocuments
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.{Operator, OperatorContext}
import zio.*
import zio.schema.annotation.description
import zio.schema.{DeriveSchema, Schema}

import McpSchema.given
import ToolSchema.readOnly

/** Per-location CSS and SVG icon: read through `core`'s `StylesOfQuery` and `IconOfQuery`, changed
  * by `ChangeStylesCommand` and `ChangeIconCommand`, which publish to S3 and invalidate the CDN
  * after the change is recorded. Whether the content is CSS or safe SVG is checked here: file
  * formats stay out of `core`.
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
      @description("The stored stylesheet. Absent when there is none.")
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
      @description(
        "Size of the document, before compression. It is published and the edge invalidated before this answers, so the change is visible rather than waiting behind the edge cache.")
      bytes: Int)

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

  /** What the operator reads for each refusal of these tools. */
  private val describe: PartialFunction[AppError, List[String]] =
    case AppError.Location(found) =>
      found.toList.map(LocationCommands.describe)

  private def slugOf(raw: String): IO[String, Slug] = ZIO
    .fromEither(Slug.parse(raw.trim))
    .mapError(LocationCommands.describe)

  /** Runs `command` through `flow` as the calling operator: recorded, then published. */
  private def changed[C](ctx: McpToolContext, slug: Slug, content: String, command: C)(
      flow: Flow.Aux[Task, C, Unit]): ZIO[OperatorContext, String, SetResponse] =
    for
      caller <- Commands.callerOf(ctx)
      _      <- Commands.run(Operator(caller, None), command)(flow)(describe)
    yield SetResponse(slug.asString, content.getBytes("UTF-8").length)

  /** Refuses SVG with scripts or event handlers. Served from portal origin so direct access runs
    * scripts.
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
      // Detect any on* event handler attribute.
      _ <-
        ZIO.when("(?s)<[^>]*\\son[a-z]+\\s*=".r.findFirstIn(lowered).isDefined)(
          ZIO.fail("The SVG carries an event handler attribute. Remove it and try again."))
    yield ()

  /** CSS custom properties schema: names, defaults, and grouping from portal's stylesheet. */
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
        slug     <- slugOf(in.slug)
        quill    <- ZIO.service[QuillSqlite]
        store    <- ZIO.service[AssetStore]
        document <-
          Commands.ask(PublishedDocuments.styles(quill, store), StylesOfQuery(slug))(describe)
      yield GetStylesResponse(slug.asString, document.content.isDefined, document.content)

  val setStyles: McpToolHandlerR[OperatorContext & AssetFlows.ChangeStyles] = McpTool(
    "set_location_styles")
    .description(
      "Replace the location's custom stylesheet and invalidate the CDN so the change is visible immediately. Sends the whole file, not a patch — call get_location_styles first when editing rather than rewriting.")
    .requireScopes(Auth.ContentWrite)
    .handleWithContext: (in: SetStylesRequest, ctx: McpToolContext) =>
      for
        slug <- slugOf(in.slug)
        flow <- ZIO.service[AssetFlows.ChangeStyles]
        out  <- changed(ctx, slug, in.css, ChangeStylesCommand(slug, in.css, Instant.now))(flow)
      yield out

  val getIcon: McpToolHandlerR[QuillSqlite & AssetStore] = McpTool("get_location_icon")
    .description(
      "The location's brand icon as SVG source, or nothing when it has none and is using the default.")
    .readOnly
    .handle: (in: GetRequest) =>
      for
        slug     <- slugOf(in.slug)
        quill    <- ZIO.service[QuillSqlite]
        store    <- ZIO.service[AssetStore]
        document <- Commands.ask(PublishedDocuments.icon(quill, store), IconOfQuery(slug))(describe)
      yield GetIconResponse(slug.asString, document.content.isDefined, document.content)

  val setIcon: McpToolHandlerR[OperatorContext & AssetFlows.ChangeIcon] = McpTool(
    "set_location_icon")
    .description(
      "Replace the location's brand icon and invalidate the CDN. The icon appears on every screen, including the loader shown before anything else renders, so a broken one is visible everywhere at once.")
    .requireScopes(Auth.ContentWrite)
    .handleWithContext: (in: SetIconRequest, ctx: McpToolContext) =>
      for
        _    <- validateSvg(in.svg)
        slug <- slugOf(in.slug)
        flow <- ZIO.service[AssetFlows.ChangeIcon]
        out  <- changed(ctx, slug, in.svg, ChangeIconCommand(slug, in.svg, Instant.now))(flow)
      yield out

  val all: List[McpToolHandlerR[QuillSqlite & AssetStore & OperatorContext & AssetFlows.All]] =
    List(cssSchema, getStyles, setStyles, getIcon, setIcon)
end AssetTools
