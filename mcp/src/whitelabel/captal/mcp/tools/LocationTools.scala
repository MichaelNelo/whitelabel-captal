package whitelabel.captal.mcp.tools

import com.jamesward.ziohttp.mcp.*
import whitelabel.captal.core.application.commands.{
  CreateLocationCommand,
  RequireFingerprintCommand
}
import whitelabel.captal.core.application.queries.{ListLocationsQuery, LocationSummary}
import whitelabel.captal.core.location.{Location, LocationId, Slug, State}
import whitelabel.captal.mcp.access.Auth
import whitelabel.captal.mcp.protocol.{McpSchema, ToolSchema}
import whitelabel.captal.platform.OperatorContext
import whitelabel.captal.platform.queries.Locations
import whitelabel.captal.platform.schema.QuillSqlite
import zio.*
import zio.schema.annotation.description
import zio.schema.{DeriveSchema, Schema}

import Adapters.{reads, runs}
import McpSchema.given
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

  private def view(location: LocationSummary): LocationView = LocationView(
    location.slug,
    location.name,
    location.active,
    location.unifiConfigured,
    location.fingerprintRequired)

  /** The slug `raw` says, as `core` has it shaped, and not one of the prefixes the bucket and the
    * distribution already give a meaning: that is theirs to say, not the location's.
    */
  private def slugOf(raw: String): IO[String, Slug] =
    Refusals
      .slugOf(raw)
      .filterOrFail(slug => !ReservedSlugs.contains(slug.asString))(
        s"Slug '${raw.trim}' is reserved: '/${raw
            .trim}/' already means something every location shares, in the assets bucket or at the distribution. Pick another.")

  val list: McpToolHandlerR[Queries.ListLocations] =
    McpTool("list_locations")
      .description(
        "Every location, with the slug the other tools take. Inactive locations are hidden unless asked for. `unifiConfigured` false means it has no access point yet, so every guest is turned away.")
      .readOnly
      .reads((in: ListRequest) => ZIO.succeed(ListLocationsQuery(in.includeInactive))):
        (_, found: List[LocationSummary]) => ZIO.succeed(ListResponse(found.map(view)))

  /** Scoped under `content:write` because that is the only write scope this server has. A location
    * is structural rather than content, and when Part 3 adds infrastructure tools it should move to
    * a scope of its own — a translator holding `content:write` should not be able to stand up a
    * portal.
    */
  val create: McpToolHandlerR[OperatorContext & LocationFlows.Create] =
    McpTool("create_location")
      .description(
        "Create a location: the portal instance the other tools hang content off. The slug is permanent — it becomes the URL prefix, the asset prefix in S3 and the AWS resource names, so it cannot be renamed later. Creates nothing else: the location starts with no translations, so add them with set_translations before deploying or the portal renders raw key names. UniFi credentials are configured separately.")
      .requireScopes(Auth.ContentWrite)
      .runs((in: CreateRequest) =>
        for
          slug <- slugOf(in.slug)
          now  <- Clock.instant
          id   <- Random.nextUUID
        yield CreateLocationCommand(LocationId(id.toString), slug, in.name, now)):
        (_, created: Location[State.Created.type]) =>
          ZIO.succeed(
            CreateResponse(created.id.asString, created.slug.asString, created.name, active = true))

  val setFingerprintRequired: McpToolHandlerR[
    OperatorContext & LocationFlows.RequireFingerprint & Queries.ListLocations] =
    McpTool("set_fingerprint_required")
      .description(
        "Whether a location's portal requires the link shortener's fingerprint on the redirect. Off, a guest whose redirect carries none is given one fixed fingerprint instead of being turned away, so their devices are told apart by MAC alone. Takes effect on the next guest, with no redeploy.")
      .requireScopes(Auth.ContentWrite)
      .runs((in: SetFingerprintRequiredRequest) =>
        for
          slug <- Refusals.slugOf(in.slug)
          now  <- Clock.instant
        yield RequireFingerprintCommand(slug, in.required, now)): (in, _: Unit) =>
        for
          found <- ZIO.serviceWithZIO[Queries.ListLocations](
            Commands.ask(_, ListLocationsQuery(includeInactive = true))())
          // The command found it a moment ago.
          location <- ZIO
            .fromOption(found.find(_.slug == in.slug.trim))
            .orDieWith(_ => IllegalStateException(in.slug))
        yield view(location)

  /** The slugs, for the prompt's catalogue and for completing the `{slug}` template argument. A
    * listing is not refused, so a failure here is a defect.
    */
  private[mcp] def slugs: ZIO[QuillSqlite, Nothing, List[String]] = ZIO
    .serviceWithZIO[QuillSqlite](quill =>
      Commands.ask(Locations.list(quill), ListLocationsQuery(includeInactive = false))())
    .orDieWith(IllegalStateException(_))
    .map(_.map(_.slug))

  val all: List[McpToolHandlerR[
    OperatorContext & LocationFlows.Create & LocationFlows.RequireFingerprint &
      Queries.ListLocations]] = List(list, create, setFingerprintRequired)
end LocationTools
