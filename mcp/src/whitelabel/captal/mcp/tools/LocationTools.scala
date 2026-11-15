package whitelabel.captal.mcp.tools

import java.util.UUID

import com.jamesward.ziohttp.mcp.*
import io.getquill.*
import whitelabel.captal.core.application.commands.{
  CreateLocationCommand,
  RequireFingerprintCommand
}
import whitelabel.captal.core.location.{LocationId, Slug}
import whitelabel.captal.mcp.access.Auth
import whitelabel.captal.mcp.protocol.{McpSchema, ToolSchema}
import whitelabel.captal.mcp.{QuillArrow, Writes}
import whitelabel.captal.platform.{AccessPointRow, LocationRow, Operator, OperatorContext}
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import zio.*
import zio.schema.annotation.description
import zio.schema.{DeriveSchema, Schema}

import McpSchema.given
import QuillArrow.->
import ToolSchema.readOnly

/** Location CRUD: entry point for all slug-based tools. Slug immutable (used in URL, S3 keys,
  * cookies).
  */
object LocationTools:
  /** Top-level prefixes the bucket and the distribution already give a meaning: the shared bundle,
    * the MCP App pages' scripts with their own edge behavior, the client's published releases, and
    * this server's own endpoint. A location there would share its prefix with them.
    */
  private val ReservedSlugs = Set("bundle", "ui", "releases", "mcp")

  final case class LocationView(
      @description("What every other tool here takes to address this location.")
      slug: String,
      @description("Display name. Not used to address anything.")
      name: String,
      @description("A retired location keeps its content but serves nothing.")
      active: Boolean,
      @description(
        "False means the location has no access point, so every guest is turned away with an error however complete its content is. Registered with configure_unifi.")
      unifiConfigured: Boolean,
      @description(
        "False means a guest whose redirect carries no fingerprint is given a fixed one and let through, rather than turned away. Set with set_fingerprint_required.")
      fingerprintRequired: Boolean)
  final case class ListRequest(
      @description("Retired locations are hidden by default; set this to see them too.")
      includeInactive: Boolean = false)
  final case class ListResponse(
      @description("Sorted by slug.")
      locations: List[LocationView])

  final case class CreateRequest(
      @description(
        "Permanent identifier, lowercase kebab-case, and not one of the reserved prefixes (bundle, ui, releases, mcp). It becomes the portal's URL prefix, the asset prefix in S3 and the AWS resource names, so it cannot be changed afterwards — a rename would orphan the assets and break every live session's cookie.")
      slug: String,
      @description("Display name. Free text, and changeable later, unlike the slug.")
      name: String)
  final case class CreateResponse(
      @description(
        "Internal id. Nothing else in this server takes it; tools address locations by slug.")
      id: String,
      @description("The slug as stored, trimmed. Use this with the other tools.")
      slug: String,
      @description("The name as stored, trimmed.")
      name: String,
      @description(
        "Always true for a new location — but it has no translations yet, so the portal would render key names until set_translations runs.")
      active: Boolean)

  final case class SetFingerprintRequiredRequest(
      @description("Which location. Call list_locations for the slugs.")
      slug: String,
      @description(
        "False lets a guest whose redirect carries no fingerprint through on a fixed one, for a location no link shortener stands in front of; true turns them away again.")
      required: Boolean)

  given Schema[LocationView] = DeriveSchema.gen
  given Schema[ListRequest] = DeriveSchema.gen
  given Schema[ListResponse] = DeriveSchema.gen
  given Schema[CreateRequest] = DeriveSchema.gen
  given Schema[CreateResponse] = DeriveSchema.gen
  given Schema[SetFingerprintRequiredRequest] = DeriveSchema.gen

  private inline def bySlugQuery = quote: (slugParam: String) =>
    query[LocationRow].filter(_.slug == slugParam).take(1)

  /** @param configured
    *   whether the location has an access point, which is stored only with its controller's
    *   settings complete.
    */
  private def view(row: LocationRow, configured: Boolean): LocationView = LocationView(
    row.slug,
    row.name,
    row.isActive == 1,
    configured,
    row.fingerprintIsRequired == 1)

  /** The slug `raw` says, as `core` has it shaped, and not one of the prefixes the bucket and the
    * distribution already give a meaning: that is theirs to say, not the location's.
    */
  private def slugOf(raw: String): IO[String, Slug] =
    ZIO
      .fromEither(Slug.parse(raw))
      .mapError(LocationCommands.describe)
      .filterOrFail(slug => !ReservedSlugs.contains(slug.asString))(
        s"Slug '${raw.trim}' is reserved: '/${raw
            .trim}/' already means something every location shares, in the assets bucket or at the distribution. Pick another.")

  private def callerOf(ctx: McpToolContext): IO[String, String] = ZIO
    .fromOption(Auth.callerId(ctx.principal))
    .orElseFail("This call carries no operator token to record the change under.")

  val list: McpToolHandlerR[QuillSqlite] = McpTool("list_locations")
    .description(
      "Every location, with the slug the other tools take. Inactive locations are hidden unless asked for. `unifiConfigured` false means it has no access point yet, so every guest is turned away.")
    .readOnly
    .handle: (in: ListRequest) =>
      ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        for
          rows       <- run(query[LocationRow]).orDie
          configured <- run(query[AccessPointRow].map(_.locationId)).orDie.map(_.toSet)
        yield ListResponse(
          rows
            .filter(r => in.includeInactive || r.isActive == 1)
            .sortBy(_.slug)
            .map(r => view(r, configured.contains(r.id))))

  /** Scoped under `content:write` because that is the only write scope this server has. A location
    * is structural rather than content, and when Part 3 adds infrastructure tools it should move to
    * a scope of its own — a translator holding `content:write` should not be able to stand up a
    * portal.
    */
  val create: McpToolHandlerR[OperatorContext & LocationFlows.Create] = McpTool("create_location")
    .description(
      "Create a location: the portal instance the other tools hang content off. The slug is permanent — it becomes the URL prefix, the asset prefix in S3 and the AWS resource names, so it cannot be renamed later. Creates nothing else: the location starts with no translations, so add them with set_translations before deploying or the portal renders raw key names. UniFi credentials are configured separately.")
    .requireScopes(Auth.ContentWrite)
    .handleWithContext: (in: CreateRequest, ctx: McpToolContext) =>
      for
        slug    <- slugOf(in.slug)
        caller  <- callerOf(ctx)
        flow    <- ZIO.service[LocationFlows.Create]
        created <-
          LocationCommands.run(
            Operator(caller, None),
            CreateLocationCommand(
              LocationId(UUID.randomUUID.toString),
              slug,
              in.name,
              java.time.Instant.now))(flow)
      yield CreateResponse(created.id.asString, created.slug.asString, created.name, active = true)

  val setFingerprintRequired
      : McpToolHandlerR[QuillSqlite & OperatorContext & LocationFlows.RequireFingerprint] = McpTool(
    "set_fingerprint_required")
    .description(
      "Whether a location's portal requires the link shortener's fingerprint on the redirect. Off, a guest whose redirect carries none is given one fixed fingerprint instead of being turned away, so their devices are told apart by MAC alone. Takes effect on the next guest, with no redeploy.")
    .requireScopes(Auth.ContentWrite)
    .handleWithContext: (in: SetFingerprintRequiredRequest, ctx: McpToolContext) =>
      for
        slug   <- ZIO.fromEither(Slug.parse(in.slug)).mapError(LocationCommands.describe)
        caller <- callerOf(ctx)
        flow   <- ZIO.service[LocationFlows.RequireFingerprint]
        _      <-
          LocationCommands.run(
            Operator(caller, None),
            RequireFingerprintCommand(slug, in.required, java.time.Instant.now))(flow)
        found <- ZIO.serviceWithZIO[QuillSqlite]: quill =>
          import quill.*
          for
            rows <- run(bySlugQuery(lift(slug.asString))).orDie
            row  <- ZIO
              .fromOption(rows.headOption)
              .orDieWith(_ => IllegalStateException(slug.asString))
            points <- run(query[AccessPointRow].filter(_.locationId == lift(row.id))).orDie
          yield view(row, points.nonEmpty)
      yield found

  /** The slugs, for the prompt's catalogue and for completing the `{slug}` template argument. */
  private[mcp] def slugs: ZIO[QuillSqlite, Nothing, List[String]] = ZIO.serviceWithZIO[QuillSqlite]:
    quill =>
      import quill.*
      run(query[LocationRow]).orDie.map(_.filter(_.isActive == 1).map(_.slug).sorted)

  val all: List[McpToolHandlerR[
    QuillSqlite & OperatorContext & LocationFlows.Create & LocationFlows.RequireFingerprint]] =
    List(list, create, setFingerprintRequired)
end LocationTools
