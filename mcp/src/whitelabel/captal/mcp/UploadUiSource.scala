package whitelabel.captal.mcp

import com.jamesward.ziohttp.mcp.*
import io.getquill.*
import whitelabel.captal.infra.schema.QuillSqlite
import whitelabel.captal.infra.schema.core.given
import whitelabel.captal.infra.schema.given
import whitelabel.captal.infra.{AdvertiserVideoRow, LocationRow}
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

  override def listResources(ctx: McpToolContext): ZIO[Any, Nothing, Chunk[ResourceDefinition]] =
    ZIO.succeed(Chunk.empty)

  override def listResourceTemplates(
      ctx: McpToolContext): ZIO[Any, Nothing, Chunk[ResourceTemplateDefinition]] = ZIO.succeed(
    Chunk(
      ResourceTemplateDefinition(
        uriTemplate = s"${UploadUi.Uri}/{videoId}",
        name = "Upload a video",
        description = Some(
          "A page for uploading one advertiser video's file, themed with that location's stylesheet."),
        mimeType = Some("text/html")
      )))

  override def readResource(
      uri: String,
      ctx: McpToolContext): ZIO[QuillSqlite & AssetStore, ToolError, Chunk[ResourceContents]] =
    if !uri.startsWith(Prefix) then
      ZIO.fail(ToolError(s"Not an upload page: $uri"))
    else
      val videoId = uri.stripPrefix(Prefix)
      page(videoId, uri).mapError(ToolError.apply)

  private def page(
      videoId: String,
      uri: String): ZIO[QuillSqlite & AssetStore, String, Chunk[ResourceContents]] = ZIO
    .serviceWithZIO[QuillSqlite]: quill =>
      import quill.*
      for
        video <- run(videoByIdQuery(lift(videoId)))
          .orDie
          .map(_.headOption)
          .someOrFail(s"No video with id '$videoId'.")
        store <- ZIO.service[AssetStore]
        key         = store.keyOf(video.videoUrl)
        contentType =
          key.reverse.takeWhile(_ != '.').reverse.toLowerCase match
            case "webm" =>
              "video/webm"
            case _ =>
              "video/mp4"
        url <- store.presignPut(key, contentType, 30.minutes)
        // A location without its own stylesheet renders on the page's own minimal styling, which
        // is the same thing the portal does.
        css <- ZIO
          .foreach(video.locationId): locationId =>
            run(locationByIdQuery(lift(locationId)))
              .orDie
              .map(_.headOption)
              .flatMap:
                case Some(location) =>
                  store.get(location.slug, Asset.Styles)
                case None =>
                  ZIO.none
          .map(_.flatten)
        html = UploadUi.render(videoId, url, contentType, css)
      yield Chunk(ResourceContents(uri, Some("text/html"), Some(html)))
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
