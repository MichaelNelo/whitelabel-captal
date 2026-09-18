package whitelabel.captal.mcp

import com.jamesward.ziohttp.mcp.*
import io.getquill.*
import whitelabel.captal.infra.schema.QuillSqlite
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
    ZIO.succeed(
      Chunk(
        ResourceDefinition(
          uri = UploadUi.Uri,
          name = "Upload videos",
          description = Some(
            "Every video still waiting for its file, each with a freshly signed URL to PUT it to."),
          mimeType = Some("text/html")
        )))

  override def listResourceTemplates(
      ctx: McpToolContext): ZIO[Any, Nothing, Chunk[ResourceTemplateDefinition]] = ZIO.succeed(
    Chunk.empty)

  override def readResource(
      uri: String,
      ctx: McpToolContext): ZIO[QuillSqlite & AssetStore, ToolError, Chunk[ResourceContents]] =
    if uri == UploadUi.Uri then
      ZIO.succeed(Chunk(ResourceContents(uri, Some("text/html"), Some(UploadUi.app()))))
    else
      ZIO.fail(ToolError(s"Not an upload page: $uri"))

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
