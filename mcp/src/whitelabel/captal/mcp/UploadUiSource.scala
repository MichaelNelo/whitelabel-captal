package whitelabel.captal.mcp

import com.jamesward.ziohttp.mcp.*
import io.getquill.*
import whitelabel.captal.infra.schema.QuillSqlite
import whitelabel.captal.infra.schema.core.given
import whitelabel.captal.infra.schema.given
import whitelabel.captal.infra.{AdvertiserVideoRow, LocalizedTextRow, LocationRow}
import zio.*
import zio.json.ast.Json

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
final class UploadUiSource extends McpResourceSource[QuillSqlite & AssetStore]:
  private val Prefix = s"${UploadUi.Uri}/"

  private inline def videoByIdQuery = quote: (idParam: String) =>
    query[AdvertiserVideoRow].filter(v => sql"${v.id}".as[String] == idParam)

  private inline def locationByIdQuery = quote: (idParam: String) =>
    query[LocationRow].filter(_.id == idParam).take(1)

  /** Not listed here. `EntityResources` enumerates one `ui://upload-video/{id}` per video still
    * waiting for a file, which is a resource per video rather than one page that lists them — the
    * listing is the place to answer "which are pending", not the page.
    *
    * The bare `ui://upload-video` stays readable because `_meta.ui.resourceUri` has nothing else it
    * can say: the declaration is built before any call has produced a video.
    */
  override def listResources(ctx: McpToolContext): ZIO[Any, Nothing, Chunk[ResourceDefinition]] =
    ZIO.succeed(Chunk.empty)

  override def listResourceTemplates(
      ctx: McpToolContext): ZIO[Any, Nothing, Chunk[ResourceTemplateDefinition]] = ZIO.succeed(
    Chunk.empty)

  override def readResource(
      uri: String,
      ctx: McpToolContext): ZIO[QuillSqlite & AssetStore, ToolError, Chunk[ResourceContents]] =
    if uri == UploadUi.Uri then
      ZIO.succeed(Chunk(ResourceContents(uri, Some("text/html"), Some(UploadUi.app()))))
    else if uri.startsWith(Prefix) then
      pageFor(uri, uri.stripPrefix(Prefix)).mapError(ToolError.apply)
    else
      ZIO.fail(ToolError(s"Not an upload page: $uri"))

  /** The title and location are rendered into the markup, so the page has them before it asks for
    * anything. The signed URL is not: that is read at the moment it is needed, which is what lets
    * it be read again when it lapses.
    */
  private def pageFor(uri: String, videoId: String)
      : ZIO[QuillSqlite, String, Chunk[ResourceContents]] = ZIO.serviceWithZIO[QuillSqlite]:
    quill =>
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
      yield Chunk(ResourceContents(uri, Some("text/html"), Some(UploadUi.app(Some(pending)))))
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

  /** Declares the Apps extension so a host knows the `ui://` resources are renderable rather than
    * documents to read.
    */
  override def capabilities: Map[String, Json.Obj] = Map("io.modelcontextprotocol/ui" -> Json.Obj())
end UploadUiSource
