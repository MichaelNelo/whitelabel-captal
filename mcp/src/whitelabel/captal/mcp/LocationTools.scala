package whitelabel.captal.mcp

import java.util.UUID

import com.jamesward.ziohttp.mcp.*
import io.getquill.*
import whitelabel.captal.infra.LocationRow
import whitelabel.captal.infra.schema.QuillSqlite
import whitelabel.captal.infra.schema.given
import zio.*
import zio.schema.{DeriveSchema, Schema}

/** Location tools.
  *
  * Every other tool in this server takes a `slug` and refuses to act when it does not resolve, so
  * until now the only way to get one was file provisioning — which is what this migration exists to
  * remove. Creating a location is the entry point to all of them.
  *
  * The slug is not a display field. It becomes the portal's URL prefix (`/<slug>/api/...`), the S3
  * key prefix for that location's assets, the suffix of its session cookie, and the ALB target
  * group and ECS service names at deploy time. That is why it is validated far more strictly than
  * the name, and why it cannot be changed afterwards: a rename would orphan the assets and leave
  * every live session holding a cookie for a path that no longer exists.
  */
object LocationTools:
  /** `captal-<slug>` is the ALB target group name, and AWS caps those at 32 characters. Enforced at
    * creation because the alternative is discovering it during a deploy, long after the location
    * has content hanging off it.
    */
  private val MaxSlugLength = 32 - "captal-".length

  /** Kebab-case, and stricter than the `[a-z0-9-]+` that file provisioning checked: no leading,
    * trailing or doubled hyphen. Those all pass the looser rule and produce URLs and bucket
    * prefixes that read as typos.
    */
  private val SlugPattern = "[a-z0-9]+(-[a-z0-9]+)*".r

  final case class LocationView(
      slug: String,
      name: String,
      active: Boolean,
      unifiConfigured: Boolean)
  final case class ListIn(includeInactive: Boolean = false)
  final case class ListOut(locations: List[LocationView])

  final case class CreateIn(slug: String, name: String)
  final case class CreateOut(id: String, slug: String, name: String, active: Boolean)

  given Schema[LocationView] = DeriveSchema.gen
  given Schema[ListIn] = DeriveSchema.gen
  given Schema[ListOut] = DeriveSchema.gen
  given Schema[CreateIn] = DeriveSchema.gen
  given Schema[CreateOut] = DeriveSchema.gen

  private inline def bySlugQuery = quote: (slugParam: String) =>
    query[LocationRow].filter(_.slug == slugParam).take(1)

  /** The host is the one field with no default anywhere: without it `UnifiClient` has nothing to
    * talk to, so its presence is what distinguishes a configured location from a bare one.
    */
  private def unifiConfigured(row: LocationRow): Boolean = row.unifiHost.exists(_.trim.nonEmpty)

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
    else
      ZIO.succeed(slug)

  private def validateName(raw: String): ZIO[Any, String, String] =
    val name = raw.trim
    ZIO.fail("Name must not be blank.").when(name.isEmpty).as(name)

  val list: McpToolHandlerR[QuillSqlite] = McpTool("list_locations")
    .description(
      "Every location, with the slug the other tools take. Inactive locations are hidden unless asked for. `unifiConfigured` false means the portal cannot authorize guests there yet.")
    .handle: (in: ListIn) =>
      ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        for rows <- run(query[LocationRow]).orDie
        yield ListOut(
          rows
            .filter(r => in.includeInactive || r.isActive == 1)
            .sortBy(_.slug)
            .map(r => LocationView(r.slug, r.name, r.isActive == 1, unifiConfigured(r))))

  /** Scoped under `content:write` because that is the only write scope this server has. A location
    * is structural rather than content, and when Part 3 adds infrastructure tools it should move to
    * a scope of its own — a translator holding `content:write` should not be able to stand up a
    * portal.
    */
  val create: McpToolHandlerR[QuillSqlite] = McpTool("create_location")
    .description(
      "Create a location: the portal instance the other tools hang content off. The slug is permanent — it becomes the URL prefix, the asset prefix in S3 and the AWS resource names, so it cannot be renamed later. Creates nothing else: the location starts with no translations, so add them with set_translations before deploying or the portal renders raw key names. UniFi credentials are configured separately.")
    .requireScopes(Auth.ContentWrite)
    .handle: (in: CreateIn) =>
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
        yield CreateOut(id, slug, name, active = true)
        end for

  val all: List[McpToolHandlerR[QuillSqlite]] = List(list, create)
end LocationTools
