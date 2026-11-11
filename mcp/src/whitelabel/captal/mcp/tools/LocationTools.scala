package whitelabel.captal.mcp.tools

import java.util.UUID

import com.jamesward.ziohttp.mcp.*
import io.getquill.*
import whitelabel.captal.mcp.access.Auth
import whitelabel.captal.mcp.protocol.{McpSchema, ToolSchema}
import whitelabel.captal.mcp.{QuillArrow, Writes}
import whitelabel.captal.platform.LocationRow
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
  /** Max slug length: 25 chars (AWS ALB target group limit of 32 minus "captal-"). */
  private val MaxSlugLength = 32 - "captal-".length

  /** Kebab-case, stricter than [a-z0-9-]+ (no leading, trailing, or doubled hyphens). */
  private val SlugPattern = "[a-z0-9]+(-[a-z0-9]+)*".r

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
        "False means no UniFi host is set, so the portal cannot authorize guests there however complete its content is. Configured outside this server.")
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
        "Permanent identifier, kebab-case, 25 characters at most. It becomes the portal's URL prefix, the asset prefix in S3 and the AWS resource names, so it cannot be changed afterwards — a rename would orphan the assets and break every live session's cookie.")
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

  /** Check if UniFi host is configured (distinguishes configured from bare location). */
  private def unifiConfigured(row: LocationRow): Boolean = row.unifiHost.exists(_.trim.nonEmpty)

  private def view(row: LocationRow): LocationView = LocationView(
    row.slug,
    row.name,
    row.isActive == 1,
    unifiConfigured(row),
    row.fingerprintIsRequired == 1)

  private def validateSlug(raw: String): ZIO[Any, String, String] =
    val slug = raw.trim
    if slug.isEmpty then
      ZIO.fail("Slug must not be blank.")
    else if slug.length > MaxSlugLength then
      ZIO.fail(
        s"Slug is ${slug
            .length} characters; the maximum is $MaxSlugLength. It becomes the ALB target group name 'captal-$slug', and AWS caps those at 32.")
    else if !SlugPattern.matches(slug) then
      ZIO.fail(
        s"Slug '$slug' is not kebab-case. Use lowercase letters, digits and single hyphens between them — no spaces, underscores, capitals, or leading, trailing or doubled hyphens.")
    else if ReservedSlugs.contains(slug) then
      ZIO.fail(
        s"Slug '$slug' is reserved: '/$slug/' already means something every location shares, in the assets bucket or at the distribution. Pick another.")
    else
      ZIO.succeed(slug)

  private def validateName(raw: String): ZIO[Any, String, String] =
    val name = raw.trim
    ZIO.fail("Name must not be blank.").when(name.isEmpty).as(name)

  val list: McpToolHandlerR[QuillSqlite] = McpTool("list_locations")
    .description(
      "Every location, with the slug the other tools take. Inactive locations are hidden unless asked for. `unifiConfigured` false means the portal cannot authorize guests there yet.")
    .readOnly
    .handle: (in: ListRequest) =>
      ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        for rows <- run(query[LocationRow]).orDie
        yield ListResponse(
          rows.filter(r => in.includeInactive || r.isActive == 1).sortBy(_.slug).map(view))

  /** Scoped under `content:write` because that is the only write scope this server has. A location
    * is structural rather than content, and when Part 3 adds infrastructure tools it should move to
    * a scope of its own — a translator holding `content:write` should not be able to stand up a
    * portal.
    */
  val create: McpToolHandlerR[QuillSqlite] = McpTool("create_location")
    .description(
      "Create a location: the portal instance the other tools hang content off. The slug is permanent — it becomes the URL prefix, the asset prefix in S3 and the AWS resource names, so it cannot be renamed later. Creates nothing else: the location starts with no translations, so add them with set_translations before deploying or the portal renders raw key names. UniFi credentials are configured separately.")
    .requireScopes(Auth.ContentWrite)
    .handle: (in: CreateRequest) =>
      ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        for
          slug <- validateSlug(in.slug)
          name <- validateName(in.name)
          // Unlike `advertisers`, this column really is UNIQUE and rqlite really does reject the
          // duplicate — but as a field inside an HTTP 200, which the driver turns into zero rows
          // affected. Without this check `Writes.expectOne` still stops the write, reporting "the
          // database rejected it silently" and leaving the operator to guess why. Verified by
          // disabling it: the row stays out, only the message degrades.
          clashes <- run(bySlugQuery(lift(slug))).orDie
          _       <-
            ZIO.when(clashes.nonEmpty)(
              ZIO.fail(
                s"A location with slug '$slug' already exists, named '${clashes
                    .head
                    .name}'. Slugs are permanent; pick another one."))
          now = java.time.Instant.now.toString
          id  = UUID.randomUUID.toString
          affected <-
            run(
              query[LocationRow].insertValue(
                lift(
                  LocationRow(
                    id = id,
                    slug = slug,
                    name = name,
                    isActive = 1,
                    createdAt = now,
                    updatedAt = now,
                    unifiApMac = None)))).orDie
          _ <- Writes.expectOne(affected, s"Creating location '$slug'")
        yield CreateResponse(id, slug, name, active = true)
        end for

  val setFingerprintRequired: McpToolHandlerR[QuillSqlite] = McpTool("set_fingerprint_required")
    .description(
      "Whether a location's portal requires the link shortener's fingerprint on the redirect. Off, a guest whose redirect carries none is given one fixed fingerprint instead of being turned away, so their devices are told apart by MAC alone. Takes effect on the next guest, with no redeploy.")
    .requireScopes(Auth.ContentWrite)
    .handle: (in: SetFingerprintRequiredRequest) =>
      ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        val slug = in.slug.trim
        val flag =
          if in.required then
            1
          else
            0
        for
          rows <- run(bySlugQuery(lift(slug))).orDie
          row  <- ZIO
            .fromOption(rows.headOption)
            .orElseFail(s"No location has the slug '$slug'. Call list_locations for the slugs.")
          affected <-
            run(
              query[LocationRow]
                .filter(_.slug == lift(slug))
                .update(
                  _.fingerprintIsRequired -> lift(flag),
                  _.updatedAt             -> lift(java.time.Instant.now.toString))).orDie
          _ <- Writes.expectOne(affected, s"Setting whether '$slug' requires a fingerprint")
        yield view(row.copy(fingerprintIsRequired = flag))
        end for

  /** The slugs, for the prompt's catalogue and for completing the `{slug}` template argument. */
  private[mcp] def slugs: ZIO[QuillSqlite, Nothing, List[String]] = ZIO.serviceWithZIO[QuillSqlite]:
    quill =>
      import quill.*
      run(query[LocationRow]).orDie.map(_.filter(_.isActive == 1).map(_.slug).sorted)

  val all: List[McpToolHandlerR[QuillSqlite]] = List(list, create, setFingerprintRequired)
end LocationTools
