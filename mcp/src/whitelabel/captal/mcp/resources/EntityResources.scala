package whitelabel.captal.mcp.resources

import com.jamesward.ziohttp.mcp.*
import io.getquill.*
import whitelabel.captal.mcp.apps.{UnifiUi, UploadUi, UploadUiSource}
import whitelabel.captal.mcp.aws.{Asset, AssetStore}
import whitelabel.captal.mcp.tools.{AdvertiserTools, SurveyTools, VideoTools}
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.core.given
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.{
  AdvertiserRow,
  AdvertiserVideoRow,
  LocalizedTextRow,
  LocationRow,
  SurveyRow
}
import zio.*
import zio.schema.codec.JsonCodec as SchemaJsonCodec

/** The entities as readable context, addressed by identity.
  *
  * Tools and resources answer different questions. A tool call is an action: the host asks the
  * operator to approve each one, which is right for a write and friction for a read. A resource is
  * context — read without a prompt, offered for completion, and browsable. The same survey is worth
  * having both ways, so this adds the reading without removing the tools.
  *
  * One hierarchy under `captal://` rather than a scheme per entity, because `readDirectory`
  * (SEP-2640) makes the hierarchy navigable: `captal://locations` lists its children and each child
  * can be opened in turn. Flat per-entity schemes would lose the containment that `list_surveys`
  * had to put back by hand — which survey belongs to which video, at which location.
  *
  * The collections answer an ordinary `resources/read` as well, because `resources/directory/read`
  * is an extension and a host without it could not open them at all.
  *
  * Registered as the server's only resource source, since `McpServer` holds one; the upload page is
  * served from here too rather than from a second source that could not be installed.
  */
final class EntityResources(upload: UploadUiSource, uiOrigin: String, scriptOrigin: String)
    extends McpResourceSource[QuillSqlite & AssetStore]:

  /** The UniFi page posts the credentials to this server, so this server's origin is the one thing
    * it has to be allowed to *reach*. Declared on the page: the host reads the policy from the
    * resource and nowhere else, which is why saying it on the tool achieved nothing.
    *
    * `scriptOrigin` is separate and is where the page's own module is loaded *from* —
    * `resourceDomains` covers `script-src`. The two are different directives and different hosts:
    * the server for the POST, the CDN for the script.
    */
  private val unifiMeta = McpAppsResourceMeta(
    csp = Some(
      McpAppsCsp(
        connectDomains = Chunk(uiOrigin),
        resourceDomains = Chunk(scriptOrigin, "https://assets.claude.ai"))),
    prefersBorder = Some(false))

  /** What the upload page reads for one video. Signed on every read: a presigned URL is arithmetic
    * over the key and the clock, not an allocation, so there is nothing to keep and nothing to
    * release — asking again is the whole expiry story.
    */
  final case class UploadTicket(
      videoId: String,
      title: String,
      slug: String,
      uploadUrl: String,
      expiresInSeconds: Long,
      styles: Option[String])

  final case class PendingVideo(videoId: String, title: String, slug: String)
  final case class PendingVideos(videos: List[PendingVideo])

  /** A collection read as a document rather than walked as a directory. Same children, so a host
    * that only knows `resources/read` sees what one that walks the hierarchy sees.
    */
  final case class DirectoryEntry(uri: String, name: String, description: Option[String])
  final case class DirectoryListing(entries: List[DirectoryEntry])

  private val Root = "captal://"
  private val JsonMime = "application/json"

  given zio.schema.Schema[UploadTicket] = zio.schema.DeriveSchema.gen
  given zio.schema.Schema[PendingVideo] = zio.schema.DeriveSchema.gen
  given zio.schema.Schema[PendingVideos] = zio.schema.DeriveSchema.gen
  given zio.schema.Schema[DirectoryEntry] = zio.schema.DeriveSchema.gen
  given zio.schema.Schema[DirectoryListing] = zio.schema.DeriveSchema.gen

  private inline def locationBySlug = quote: (slugParam: String) =>
    query[LocationRow].filter(_.slug == slugParam).take(1)

  private def encode[A](value: A)(using schema: zio.schema.Schema[A]): String =
    SchemaJsonCodec.jsonEncoder(schema).encodeJson(value, None).toString

  private def contents(
      uri: String,
      body: String,
      mime: String = JsonMime): Chunk[ResourceContents] = Chunk(
    ResourceContents(uri, Some(mime), Some(body)))

  /** A collection, readable both ways. Returned by [[readResource]]. */
  private def directory(uri: String, name: String, description: String): ResourceDefinition =
    ResourceDefinition(uri, name, Some(description), Some(JsonMime))

  /** One upload page per video still waiting for a file, rather than one page that lists them. The
    * listing is where "which are pending" belongs, and a resource that addresses exactly one video
    * cannot hand out a signed URL for another.
    */
  private def pendingPages: ZIO[QuillSqlite, Nothing, Chunk[ResourceDefinition]] = VideoTools
    .pending(None)
    .map: waiting =>
      Chunk.fromIterable(
        waiting
          .map: video =>
            upload.pageDefinition(
              videoId = video.videoId,
              name = video.title,
              description =
                s"Upload the file for this video at ${video
                    .slug}. It stays out of rotation until the file arrives."
            )
          .sortBy(_.name))

  override def listResources(
      ctx: McpToolContext): ZIO[QuillSqlite & AssetStore, Nothing, Chunk[ResourceDefinition]] =
    pendingPages
      .zipWith(upload.listResources(ctx))(_ ++ _)
      .map(
        _ ++
          Chunk(
            directory(
              s"${Root}locations",
              "Locations",
              "Every location. Open one for what it still needs before it is worth deploying."),
            directory(
              s"${Root}advertisers",
              "Advertisers",
              "Every advertiser. They are deployment-wide, not scoped to a location."),
            directory(
              s"${Root}surveys",
              "Surveys",
              "Every survey. Only advertiser surveys belong to a location, through their video."),
            // Listed here; the page needs the ticket from `configure_unifi`.
            McpApps.resource(
              UnifiUi.AppUri,
              "Configurar UniFi",
              unifiMeta,
              Some("The credentials form. Opened by configure_unifi, which is what supplies the location and the ticket.")
            )
          ))

  override def listResourceTemplates(
      ctx: McpToolContext): ZIO[Any, Nothing, Chunk[ResourceTemplateDefinition]] = upload
    .listResourceTemplates(ctx)
    .map(
      _ ++
        Chunk(
          ResourceTemplateDefinition(
            s"${Root}locations/{slug}",
            "Location readiness",
            Some("One location and what it is still missing: translations per locale, branding, videos, and the advertisers it could draw from."),
            Some(JsonMime)
          ),
          ResourceTemplateDefinition(
            s"${Root}advertisers/{advertiserId}",
            "Advertiser",
            Some("One advertiser with its rotation priority and how many videos it has active."),
            Some(JsonMime)
          ),
          ResourceTemplateDefinition(
            s"${Root}surveys/{surveyId}",
            "Survey",
            Some("One survey with every question in display order, its options, rules and answer count."),
            Some(JsonMime))
        ))

  override def readResource(
      uri: String,
      ctx: McpToolContext): ZIO[QuillSqlite & AssetStore, ToolError, Chunk[ResourceContents]] =
    if uri == UnifiUi.Uri then
      // Static in its data — it carries none, because the ticket and the location arrive with the
      // result of the call that opened it — but the resource hints still come from the published
      // entry, so the markup is not a constant either.
      ZIO.serviceWithZIO[AssetStore](store =>
        store
          .scriptPreloads(UnifiUi.EntryScript)
          .map(preloads =>
            Chunk(
              McpApps.contents(
                UnifiUi.AppUri,
                UnifiUi.app(store.scriptUrl, store.scriptOrigin, preloads),
                unifiMeta))))
    else if uri.startsWith(UploadUi.Uri) then
      upload.readResource(uri, ctx)
    else
      uri.stripPrefix(Root).split('/').toList match
        case "locations" :: slug :: Nil =>
          Readiness.of(slug).map(r => contents(uri, encode(r))).mapError(ToolError.apply)
        case "advertisers" :: id :: Nil =>
          advertiser(uri, id)
        case "surveys" :: id :: Nil =>
          survey(uri, id)
        case "videos" :: "pending" :: Nil =>
          pendingVideos(uri).mapError(ToolError.apply)
        case "videos" :: id :: "upload" :: Nil =>
          uploadTicket(uri, id).mapError(ToolError.apply)
        // Collections served via ordinary readResource; reuses readDirectory for consistency.
        case ("locations" | "advertisers" | "surveys") :: Nil =>
          readDirectory(uri, ctx).map: children =>
            contents(
              uri,
              encode(
                DirectoryListing(
                  children.toList.map(c => DirectoryEntry(c.uri, c.name, c.description)))))
        case _ =>
          ZIO.fail(ToolError(s"No resource at $uri"))

  /** Reads advertiser by id. */
  private def advertiser(uri: String, id: String)
      : ZIO[QuillSqlite, ToolError, Chunk[ResourceContents]] = ZIO.serviceWithZIO[QuillSqlite]:
    quill =>
      import quill.*
      for
        rows <- run(query[AdvertiserRow].filter(_.id == lift(id))).orDie
        row  <- ZIO.succeed(rows.headOption).someOrFail(ToolError(s"No advertiser with id '$id'."))
        videos <- run(query[AdvertiserVideoRow]).orDie
        view = AdvertiserTools.AdvertiserView(
          row.id,
          row.name,
          row.priority,
          row.isActive == 1,
          videos.count(v => v.advertiserId.contains(row.id) && v.status == "active"))
      yield contents(uri, encode(view))

  private def survey(
      uri: String,
      id: String): ZIO[QuillSqlite, ToolError, Chunk[ResourceContents]] = SurveyTools
    .viewOf(id)
    .map(v => contents(uri, encode(v)))
    .mapError(ToolError.apply)

  /** Pending videos, matching `list_pending_uploads`. */
  private def pendingVideos(uri: String): ZIO[QuillSqlite, String, Chunk[ResourceContents]] =
    VideoTools
      .pending(None)
      .map: waiting =>
        contents(
          uri,
          encode(
            PendingVideos(
              waiting.map(v => PendingVideo(v.videoId, v.title, v.slug)).sortBy(_.title))))

  /** Signed upload URL for one video. One signature per read. */
  private def uploadTicket(
      uri: String,
      videoId: String): ZIO[QuillSqlite & AssetStore, String, Chunk[ResourceContents]] = ZIO
    .serviceWithZIO[QuillSqlite]: quill =>
      import quill.*
      for
        videos <- run(query[AdvertiserVideoRow]).orDie
        video  <- ZIO
          .succeed(videos.find(_.id.toString == videoId))
          .someOrFail(s"No video with id '$videoId'.")
        _ <- ZIO
          .fail(s"Video '$videoId' is ${video.status}; only a pending video has a file to upload.")
          .when(video.status != "pending")
        locations <- run(query[LocationRow]).orDie
        titles    <- run(query[LocalizedTextRow].filter(_.entityId == lift(videoId))).orDie
        store     <- ZIO.service[AssetStore]
        key = store.keyOf(video.videoUrl)
        url <- store.presignPut(key, VideoTools.UploadValidFor)
        slug = video.locationId.flatMap(id => locations.find(_.id == id)).map(_.slug)
        styles <- ZIO.foreach(slug)(store.get(_, Asset.Styles)).map(_.flatten)
        ticket = UploadTicket(
          videoId = videoId,
          title = titles.map(_.value).minOption.getOrElse(videoId),
          slug = slug.getOrElse("—"),
          uploadUrl = url,
          expiresInSeconds = VideoTools.UploadValidFor.toSeconds,
          styles = styles
        )
      yield contents(uri, encode(ticket))
      end for

  /** `resources/directory/read`: the children of a directory, each either a leaf or another
    * directory. This is what makes the hierarchy worth having — the relationships are walkable
    * rather than described.
    */
  override def readDirectory(
      uri: String,
      ctx: McpToolContext): ZIO[QuillSqlite & AssetStore, ToolError, Chunk[ResourceDefinition]] =
    uri.stripPrefix(Root).split('/').toList match
      case "locations" :: Nil =>
        ZIO.serviceWithZIO[QuillSqlite]: quill =>
          import quill.*
          run(query[LocationRow])
            .orDie
            .map(
              _.filter(_.isActive == 1)
                .sortBy(_.slug)
                .map(l =>
                  ResourceDefinition(
                    s"${Root}locations/${l.slug}",
                    l.name,
                    Some(s"What '${l.slug}' still needs."),
                    Some(JsonMime)))
                .pipe(Chunk.fromIterable))

      case "advertisers" :: Nil =>
        ZIO.serviceWithZIO[QuillSqlite]: quill =>
          import quill.*
          run(query[AdvertiserRow])
            .orDie
            .map(
              _.filter(_.isActive == 1)
                .sortBy(_.name)
                .map(a =>
                  ResourceDefinition(
                    s"${Root}advertisers/${a.id}",
                    a.name,
                    Some(s"Rotation priority ${a.priority}."),
                    Some(JsonMime)))
                .pipe(Chunk.fromIterable))

      case "surveys" :: Nil =>
        ZIO.serviceWithZIO[QuillSqlite]: quill =>
          import quill.*
          run(query[SurveyRow])
            .orDie
            .map(
              _.filter(_.isActive == 1)
                .sortBy(s => (s.category, s.name.getOrElse("")))
                .map(s =>
                  ResourceDefinition(
                    s"${Root}surveys/${s.id.asString}",
                    s.name.getOrElse(s.category),
                    Some(s"${s.category} survey."),
                    Some(JsonMime)))
                .pipe(Chunk.fromIterable))

      case _ =>
        ZIO.fail(ToolError(s"Not a directory: $uri"))

  /** Completion values for the template arguments. The default implementation returns nothing, so
    * without this the templates are typed by hand — which is the ergonomics resources were chosen
    * for in the first place.
    */
  override def complete(
      ref: CompletionRef,
      argument: CompletionArgument,
      ctx: McpToolContext): ZIO[QuillSqlite & AssetStore, Nothing, CompletionResult] =
    val prefix = argument.value
    val values: ZIO[QuillSqlite, Nothing, Chunk[String]] =
      argument.name match
        case "slug" =>
          ZIO.serviceWithZIO[QuillSqlite]: quill =>
            import quill.*
            run(query[LocationRow]).orDie.map(rows => Chunk.fromIterable(rows.map(_.slug).sorted))
        case "advertiserId" =>
          ZIO.serviceWithZIO[QuillSqlite]: quill =>
            import quill.*
            run(query[AdvertiserRow]).orDie.map(rows => Chunk.fromIterable(rows.map(_.id)))
        case "surveyId" =>
          ZIO.serviceWithZIO[QuillSqlite]: quill =>
            import quill.*
            run(query[SurveyRow]).orDie.map(rows => Chunk.fromIterable(rows.map(_.id.asString)))
        case _ =>
          ZIO.succeed(Chunk.empty)
    values.map(all =>
      CompletionResult(CompletionValues(values = all.filter(_.startsWith(prefix)).take(100))))
  end complete

end EntityResources

extension [A](self: A)
  private def pipe[B](f: A => B): B = f(self)
