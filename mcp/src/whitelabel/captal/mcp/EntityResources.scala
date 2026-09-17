package whitelabel.captal.mcp

import com.jamesward.ziohttp.mcp.*
import io.getquill.*
import whitelabel.captal.infra.schema.QuillSqlite
import whitelabel.captal.infra.schema.core.given
import whitelabel.captal.infra.schema.given
import whitelabel.captal.infra.{AdvertiserRow, AdvertiserVideoRow, LocationRow, SurveyRow}
import zio.*
import zio.json.ast.Json
import zio.schema.codec.JsonCodec as SchemaJsonCodec

/** The entities as readable context, addressed by identity.
  *
  * Tools and resources answer different questions. A tool call is an action: the host asks the
  * operator to approve each one, which is right for a write and friction for a read. A resource is
  * context — read without a prompt, offered for completion, and browsable. The same survey is worth
  * having both ways, so this adds the reading without removing the tools.
  *
  * One hierarchy under `captal://` rather than a scheme per entity, because `readDirectory`
  * (SEP-2640) makes the hierarchy navigable: `captal://locations` lists its children, a child
  * marked `inode/directory` can be opened in turn. Flat per-entity schemes would lose the
  * containment that `list_surveys` had to put back by hand — which survey belongs to which video,
  * at which location.
  *
  * Registered as the server's only resource source, since `McpServer` holds one; the upload page is
  * served from here too rather than from a second source that could not be installed.
  */
final class EntityResources(upload: UploadUiSource)
    extends McpResourceSource[QuillSqlite & AssetStore]:

  private val Root = "captal://"
  private val Directory = "inode/directory"
  private val JsonMime = "application/json"

  private inline def locationBySlug = quote: (slugParam: String) =>
    query[LocationRow].filter(_.slug == slugParam).take(1)

  private def encode[A](value: A)(using schema: zio.schema.Schema[A]): String =
    SchemaJsonCodec.jsonEncoder(schema).encodeJson(value, None).toString

  private def contents(
      uri: String,
      body: String,
      mime: String = JsonMime): Chunk[ResourceContents] = Chunk(
    ResourceContents(uri, Some(mime), Some(body)))

  private def directory(uri: String, name: String, description: String): ResourceDefinition =
    ResourceDefinition(uri, name, Some(description), Some(Directory))

  override def listResources(ctx: McpToolContext): ZIO[Any, Nothing, Chunk[ResourceDefinition]] =
    upload
      .listResources(ctx)
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
              "Every survey. Only advertiser surveys belong to a location, through their video.")
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
    if uri.startsWith(UploadUi.Uri) then
      upload.readResource(uri, ctx)
    else
      uri.stripPrefix(Root).split('/').toList match
        case "locations" :: slug :: Nil =>
          Readiness.of(slug).map(r => contents(uri, encode(r))).mapError(ToolError.apply)
        case "advertisers" :: id :: Nil =>
          advertiser(uri, id)
        case "surveys" :: id :: Nil =>
          survey(uri, id)
        case _ =>
          ZIO.fail(ToolError(s"No resource at $uri"))

  /** Reuses the tools' own read paths rather than querying again, so a resource and the tool that
    * answers the same question cannot drift apart.
    */
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
          videos.count(v => v.advertiserId.contains(row.id) && v.isActive == 1))
      yield contents(uri, encode(view))

  private def survey(
      uri: String,
      id: String): ZIO[QuillSqlite, ToolError, Chunk[ResourceContents]] = SurveyTools
    .viewOf(id)
    .map(v => contents(uri, encode(v)))
    .mapError(ToolError.apply)

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

  override def capabilities: Map[String, Json.Obj] = upload.capabilities
end EntityResources

extension [A](self: A)
  private def pipe[B](f: A => B): B = f(self)
