package whitelabel.captal.mcp

import java.util.UUID

import com.jamesward.ziohttp.mcp.*
import io.getquill.*
import whitelabel.captal.infra.schema.QuillSqlite
import whitelabel.captal.infra.schema.given
import whitelabel.captal.infra.{AdvertiserRow, AdvertiserVideoRow}
import zio.*
import zio.schema.{DeriveSchema, Schema}

import McpSchema.given

/** Advertiser tools.
  *
  * `advertisers` has no unique constraint of any kind: two rows may share a name, and nothing stops
  * a duplicate. Under file provisioning the YAML filename acted as the key and `IdGenerator` turned
  * it into a deterministic id, so re-applying a file updated rather than duplicated. With explicit
  * CRUD that guard is gone, which is why `create_advertiser` refuses a name already in use instead
  * of trusting the schema to reject it.
  */
object AdvertiserTools:
  final case class AdvertiserView(
      id: String,
      name: String,
      priority: Int,
      active: Boolean,
      activeVideos: Int)
  final case class ListIn(includeInactive: Boolean = false)
  final case class ListOut(advertisers: List[AdvertiserView])

  final case class CreateIn(name: String, priority: Int)
  final case class CreateOut(id: String, name: String, priority: Int)

  final case class UpdateIn(id: String, name: Option[String], priority: Option[Int])
  final case class UpdateOut(id: String, name: String, priority: Int)

  final case class SetActiveIn(id: String, active: Boolean)
  final case class SetActiveOut(id: String, active: Boolean, activeVideos: Int)

  given Schema[AdvertiserView] = DeriveSchema.gen
  given Schema[ListIn] = DeriveSchema.gen
  given Schema[ListOut] = DeriveSchema.gen
  given Schema[CreateIn] = DeriveSchema.gen
  given Schema[CreateOut] = DeriveSchema.gen
  given Schema[UpdateIn] = DeriveSchema.gen
  given Schema[UpdateOut] = DeriveSchema.gen
  given Schema[SetActiveIn] = DeriveSchema.gen
  given Schema[SetActiveOut] = DeriveSchema.gen

  private inline def byIdQuery = quote: (idParam: String) =>
    query[AdvertiserRow].filter(_.id == idParam)

  private inline def byNameQuery = quote: (nameParam: String) =>
    query[AdvertiserRow].filter(_.name == nameParam)

  /** Counted in SQL rather than fetched: the row carries domain-typed columns this module has no
    * decoders for, and the count is all anyone needs.
    */
  private inline def activeVideoCountQuery = quote: (advertiserIdParam: String) =>
    query[AdvertiserVideoRow]
      .filter(v => v.advertiserId.contains(advertiserIdParam) && v.isActive == 1)
      .size

  private def find(id: String): ZIO[QuillSqlite, String, AdvertiserRow] = ZIO.serviceWithZIO[
    QuillSqlite]: quill =>
    import quill.*
    run(byIdQuery(lift(id)))
      .orDie
      .map(_.headOption)
      .someOrFail(s"No advertiser with id '$id'. Call list_advertisers to see what exists.")

  private def countActiveVideos(id: String): ZIO[QuillSqlite, Nothing, Int] = ZIO.serviceWithZIO[
    QuillSqlite]: quill =>
    import quill.*
    run(activeVideoCountQuery(lift(id))).orDie.map(_.toInt)

  private def validateName(name: String): ZIO[Any, String, String] =
    val trimmed = name.trim
    ZIO.fail("Name must not be blank.").when(trimmed.isEmpty).as(trimmed)

  /** Priority weights the rotation: `VideoRepositoryQuill` divides each advertiser's priority by
    * the sum across active advertisers. Zero means the advertiser is never drawn, which is a valid
    * but surprising choice, so it is allowed while negatives are not.
    */
  private def validatePriority(priority: Int): ZIO[Any, String, Int] = ZIO
    .fail(s"Priority must be zero or greater, got $priority.")
    .when(priority < 0)
    .as(priority)

  val list: McpToolHandlerR[QuillSqlite] = McpTool("list_advertisers")
    .description(
      "Advertisers with their rotation priority and how many active videos each one has. Inactive advertisers are hidden unless asked for.")
    .handle: (in: ListIn) =>
      ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        for
          rows <- run(query[AdvertiserRow]).orDie
          visible = rows.filter(r => in.includeInactive || r.isActive == 1).sortBy(_.name)
          views <-
            ZIO.foreach(visible): row =>
              countActiveVideos(row.id).map: videos =>
                AdvertiserView(row.id, row.name, row.priority, row.isActive == 1, videos)
        yield ListOut(views)

  val create: McpToolHandlerR[QuillSqlite] = McpTool("create_advertiser")
    .description(
      "Register an advertiser. Priority weights the video rotation against the other active advertisers; zero means never drawn. Names must be unique. Read skill://add-advertiser/SKILL.md first.")
    .requireScopes(Auth.ContentWrite)
    .handle: (in: CreateIn) =>
      ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        for
          name     <- validateName(in.name)
          priority <- validatePriority(in.priority)
          // Enforced here because the table has no unique index; nothing would reject a duplicate.
          clashes <- run(byNameQuery(lift(name))).orDie
          _       <-
            ZIO.when(clashes.nonEmpty)(
              ZIO.fail(
                s"An advertiser named '$name' already exists (id ${clashes
                    .head
                    .id}). Use update_advertiser to change it."))
          now = java.time.Instant.now.toString
          id  = UUID.randomUUID.toString
          affected <-
            run(
              query[AdvertiserRow].insertValue(
                lift(AdvertiserRow(id, name, priority, 1, now, now)))).orDie
          _ <- Writes.expectOne(affected, s"Creating advertiser '$name'")
        yield CreateOut(id, name, priority)

  val update: McpToolHandlerR[QuillSqlite] = McpTool("update_advertiser")
    .description("Change an advertiser's name or rotation priority. Omitted fields are left alone.")
    .requireScopes(Auth.ContentWrite)
    .handle: (in: UpdateIn) =>
      ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        for
          existing <- find(in.id)
          _        <-
            ZIO.when(in.name.isEmpty && in.priority.isEmpty)(
              ZIO.fail("Nothing to update: give a name, a priority, or both."))
          name     <- ZIO.foreach(in.name)(validateName).map(_.getOrElse(existing.name))
          priority <- ZIO.foreach(in.priority)(validatePriority).map(_.getOrElse(existing.priority))
          clashes  <- run(byNameQuery(lift(name))).orDie
          _        <-
            ZIO.when(clashes.exists(_.id != in.id))(
              ZIO.fail(s"Another advertiser is already named '$name'."))
          now = java.time.Instant.now.toString
          affected <-
            run(
              byIdQuery(lift(in.id)).update(
                _.name      -> lift(name),
                _.priority  -> lift(priority),
                _.updatedAt -> lift(now))).orDie
          _ <- Writes.expectOne(affected, s"Updating advertiser '${in.id}'")
        yield UpdateOut(in.id, name, priority)
        end for

  val setActive: McpToolHandlerR[QuillSqlite] = McpTool("set_advertiser_active")
    .description(
      "Retire an advertiser or bring it back. Retiring also takes its videos out of the rotation, since the video query joins on the advertiser being active. See skill://add-advertiser/SKILL.md.")
    .requireScopes(Auth.ContentWrite)
    .handle: (in: SetActiveIn) =>
      ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        for
          _ <- find(in.id)
          now  = java.time.Instant.now.toString
          flag =
            if in.active then
              1
            else
              0
          affected <-
            run(byIdQuery(lift(in.id)).update(_.isActive -> lift(flag), _.updatedAt -> lift(now)))
              .orDie
          _ <- Writes.expectOne(affected, s"Setting advertiser '${in.id}' active=${in.active}")
          // Reported so the operator sees the blast radius rather than discovering it in the portal.
          videos <- countActiveVideos(in.id)
        yield SetActiveOut(in.id, in.active, videos)

  val all: List[McpToolHandlerR[QuillSqlite]] = List(list, create, update, setActive)
end AdvertiserTools
