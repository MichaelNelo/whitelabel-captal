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
    if uri == UploadUi.Uri then
      pending(uri).mapError(ToolError.apply)
    else if !uri.startsWith(Prefix) then
      ZIO.fail(ToolError(s"Not an upload page: $uri"))
    else
      val videoId = uri.stripPrefix(Prefix)
      page(videoId, uri).mapError(ToolError.apply)

  /** Every video with `is_active = 0`, which is what "created but never uploaded" looks like.
    *
    * Signed here rather than reusing the URL the tool returned, so a page opened after the first
    * signature expired still works instead of failing at the PUT.
    */
  private def pending(uri: String): ZIO[QuillSqlite & AssetStore, String, Chunk[ResourceContents]] =
    ZIO.serviceWithZIO[QuillSqlite]: quill =>
      import quill.*
      for
        videos    <- run(query[AdvertiserVideoRow].filter(_.isActive == 0)).orDie
        locations <- run(query[LocationRow]).orDie
        titles    <- run(query[LocalizedTextRow].filter(_.isActive == 1)).orDie
        slugOf = locations.map(l => l.id -> l.slug).toMap
        // Whatever locale comes first; the page is for an operator picking their own upload out of
        // a list, not for a guest, so any of them identifies it.
        titleOf = titles.groupBy(_.entityId).view.mapValues(_.map(_.value).minOption).toMap
        store <- ZIO.service[AssetStore]
        items <-
          ZIO.foreach(videos.toList): video =>
            val id = video.id.toString
            val key = store.keyOf(video.videoUrl)
            val contentType =
              key.reverse.takeWhile(_ != '.').reverse.toLowerCase match
                case "webm" =>
                  "video/webm"
                case _ =>
                  "video/mp4"
            store
              .presignPut(key, contentType, 30.minutes)
              .map(url =>
                UploadUi.Pending(
                  videoId = id,
                  title = titleOf
                    .get(id)
                    .flatten
                    .getOrElse(key.reverse.takeWhile(_ != '/').reverse),
                  slug = video.locationId.flatMap(slugOf.get).getOrElse("—"),
                  uploadUrl = url,
                  contentType = contentType
                ))
        // Only when every pending video belongs to one location: with several there is no single
        // stylesheet that would be right, and a page themed as the wrong client is worse than one
        // themed as none.
        css <-
          videos.flatMap(_.locationId).distinct.toList match
            case locationId :: Nil =>
              slugOf.get(locationId).fold(ZIO.none)(store.get(_, Asset.Styles))
            case _ =>
              ZIO.none
        html = UploadUi.renderPending(items, css)
      yield Chunk(ResourceContents(uri, Some("text/html"), Some(html)))
      end for

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
