package whitelabel.captal.mcp

import com.jamesward.ziohttp.mcp.*
import io.getquill.*
import whitelabel.captal.infra.schema.QuillSqlite
import whitelabel.captal.infra.schema.core.given
import whitelabel.captal.infra.schema.given
import whitelabel.captal.infra.{AdvertiserVideoRow, LocalizedTextRow, LocationRow}
import zio.*

/** Serves the upload page, one URI per video.
  *
  * A resource source rather than a template handler because the page is built from the video row, a
  * freshly signed URL and the location's stylesheet, and `McpResourceTemplate.read` runs with no
  * environment — it cannot reach the database or S3.
  *
  * Making each URI self-contained also sidesteps the part of the Apps extension this codebase has
  * not verified against a real host: how a rendered app receives the result of the call that opened
  * it. Here it receives nothing. Everything it needs is already in the page.
  *
  * The URL is signed at read time rather than reused from the tool result, so reopening the page
  * after the first signature expired works instead of failing at the PUT.
  */
/** @param scriptOrigin
  *   Where the page's own module comes from. Taken in the constructor rather than read off
  *   `AssetStore` where it is used, because the CSP goes into the resource *definition* too, and
  *   `listResources` runs with no environment to ask.
  */
final class UploadUiSource(uploadOrigin: String, scriptOrigin: String)
    extends McpResourceSource[QuillSqlite & AssetStore]:
  private val Prefix = s"${UploadUi.Uri}/"

  /** What the page may reach, declared on the page rather than on the tool that opens it.
    *
    * The host blocks every origin by default, so without this the PUT fails as `blocked:csp` at the
    * moment the operator picks a file and nowhere earlier. The page cannot widen this itself, which
    * is the point of declaring it server-side.
    */
  private val appMeta = McpAppsResourceMeta(
    csp = Some(
      McpAppsCsp(
        connectDomains = Chunk(uploadOrigin),
        // `resourceDomains` maps to `script-src` as well as to images, styles, fonts and media —
        // which is what lets the page load its module from the CDN instead of carrying it inline.
        // Miss the origin out and the module is blocked and the page renders inert, with no error
        // the operator can see; it is a worse failure than the PUT being blocked, which at least
        // happens while they are looking.
        //
        // `assets.claude.ai` is where the host serves Anthropic Sans from. Without it the
        // `@font-face` rules handed over in the handshake cannot load, and the page sits beside a
        // conversation in a different typeface.
        resourceDomains = Chunk(scriptOrigin, "https://assets.claude.ai")
      )),
    // No card around the widget. Claude is already borderless by default, but other hosts are not,
    // and a page that paints no background of its own looks wrong inside a border it did not ask
    // for.
    prefersBorder = Some(false)
  )

  private def page(uri: String, html: String): Chunk[ResourceContents] = Chunk(
    McpApps.contents(ToolSchema.uiUri(uri), html, appMeta))

  /** One pending video's page as a listing entry.
    *
    * Built here rather than by [[EntityResources]], which enumerates them, so that the policy a
    * page is listed with is the one it is served with. Two places constructing the same resource is
    * how a listing and a read come to disagree about what the page may reach.
    */
  def pageDefinition(videoId: String, name: String, description: String): ResourceDefinition =
    McpApps.resource(ToolSchema.uiUri(s"$Prefix$videoId"), name, appMeta, Some(description))

  private inline def videoByIdQuery = quote: (idParam: String) =>
    query[AdvertiserVideoRow].filter(v => sql"${v.id}".as[String] == idParam)

  private inline def locationByIdQuery = quote: (idParam: String) =>
    query[LocationRow].filter(_.id == idParam).take(1)

  /** The bare page, listed because it is what `_meta.ui.resourceUri` names.
    *
    * It used to return nothing, on the reasoning that the per-video URIs `EntityResources`
    * enumerates are the useful entry points and the bare one stays readable regardless. Readable
    * turned out not to be enough: a host resolves a tool's declared template against this listing,
    * and with the URI absent `create_advertiser_video` succeeded and the render failed — while
    * `resources/read` on that exact URI returned the page. `ui://configure-unifi` was listed and
    * did not have the problem, which is what the asymmetry pointed at.
    *
    * So a declared template is listed, and the listing still answers "which videos are pending"
    * through the per-video URIs. The two say different things and both are needed.
    */
  val bareDefinition: ResourceDefinition = McpApps.resource(
    UploadUi.AppUri,
    "Subir video",
    appMeta,
    Some(
      "The upload page. Opened from create_advertiser_video or resume_video_upload it takes the video from that call; opened on its own it has nothing to upload — pick a pending video from the listing instead.")
  )

  override def listResources(ctx: McpToolContext): ZIO[Any, Nothing, Chunk[ResourceDefinition]] =
    ZIO.succeed(Chunk(bareDefinition))

  override def listResourceTemplates(
      ctx: McpToolContext): ZIO[Any, Nothing, Chunk[ResourceTemplateDefinition]] = ZIO.succeed(
    Chunk.empty)

  override def readResource(
      uri: String,
      ctx: McpToolContext): ZIO[QuillSqlite & AssetStore, ToolError, Chunk[ResourceContents]] =
    if uri == UploadUi.Uri then
      ZIO.serviceWith[AssetStore](store => page(uri, UploadUi.app(store.scriptUrl)))
    else if uri.startsWith(Prefix) then
      pageFor(uri, uri.stripPrefix(Prefix)).mapError(ToolError.apply)
    else
      ZIO.fail(ToolError(s"Not an upload page: $uri"))

  /** The title and location are rendered into the markup, so the page has them before it asks for
    * anything. The signed URL is not: that is read at the moment it is needed, which is what lets
    * it be read again when it lapses.
    */
  private def pageFor(
      uri: String,
      videoId: String): ZIO[QuillSqlite & AssetStore, String, Chunk[ResourceContents]] = ZIO
    .serviceWithZIO[QuillSqlite]: quill =>
      import quill.*
      for
        videos    <- run(videoByIdQuery(lift(videoId))).orDie
        video     <- ZIO.succeed(videos.headOption).someOrFail(s"No video with id '$videoId'.")
        locations <- run(query[LocationRow]).orDie
        titles    <- run(query[LocalizedTextRow].filter(_.entityId == lift(videoId))).orDie
        pending = UploadUi.PendingVideo(
          videoId = videoId,
          title = titles.map(_.value).minOption.getOrElse(videoId),
          slug = video
            .locationId
            .flatMap(id => locations.find(_.id == id))
            .map(_.slug)
            .getOrElse("—")
        )
        store <- ZIO.service[AssetStore]
      yield page(uri, UploadUi.app(store.scriptUrl, Some(pending)))
      end for

  override def readDirectory(
      uri: String,
      ctx: McpToolContext): ZIO[QuillSqlite & AssetStore, ToolError, Chunk[ResourceDefinition]] =
    ZIO.succeed(Chunk.empty)

  override def complete(
      ref: CompletionRef,
      argument: CompletionArgument,
      ctx: McpToolContext): ZIO[Any, Nothing, CompletionResult] = ZIO.succeed(
    CompletionResult(CompletionValues(values = Chunk.empty)))

end UploadUiSource
