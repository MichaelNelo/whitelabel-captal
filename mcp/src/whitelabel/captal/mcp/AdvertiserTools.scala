package whitelabel.captal.mcp

import java.util.UUID

import com.jamesward.ziohttp.mcp.*
import io.getquill.*
import whitelabel.captal.infra.AdvertiserRow
import whitelabel.captal.infra.schema.QuillSqlite
import whitelabel.captal.infra.schema.given
import zio.*
import zio.schema.annotation.description
import zio.schema.{DeriveSchema, Schema}

import McpSchema.given
import ToolSchema.readOnly

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
      @description("What create_advertiser_video takes to attach a video to this advertiser.")
      id: String,
      @description("Display name. Unique, because nothing in the schema makes it so.")
      name: String,
      @description(
        "Rotation weight against the other active advertisers, as a share of their total. Zero means never drawn.")
      priority: Int,
      @description("A retired advertiser keeps its rows but is drawn by nothing.")
      active: Boolean,
      @description(
        "Videos of this advertiser currently in rotation. Zero with active true means the advertiser can be drawn and has nothing to show.")
      activeVideos: Int)
  final case class ListRequest(
      @description("Retired advertisers are hidden by default; set this to see them too.")
      includeInactive: Boolean = false)
  final case class ListResponse(
      @description("Sorted by name.")
      advertisers: List[AdvertiserView])

  final case class CreateRequest(
      @description(
        "Display name, and the identity this tool enforces: a name already in use is refused.")
      name: String,
      @description(
        "Rotation weight against the other active advertisers. Ten is the usual value; zero registers the advertiser without ever showing it.")
      priority: Int)
  final case class CreateResponse(
      @description("The new advertiser's id, which create_advertiser_video takes.")
      id: String,
      @description("The name as stored, trimmed.")
      name: String,
      @description("The rotation weight as stored.")
      priority: Int)

  final case class UpdateRequest(
      @description("Which advertiser to change. Call list_advertisers for the ids.")
      id: String,
      @description("New display name. Omit to leave it alone. Still has to be unique.")
      name: Option[String],
      @description("New rotation weight. Omit to leave it alone.")
      priority: Option[Int])
  final case class UpdateResponse(
      @description("The advertiser changed.")
      id: String,
      @description("The name after the change, whether or not it was the field updated.")
      name: String,
      @description("The rotation weight after the change.")
      priority: Int)

  final case class SetActiveRequest(
      @description("Which advertiser to retire or bring back.")
      id: String,
      @description(
        "False retires it. Nothing is deleted — answers already given stay attached to their questions — but the advertiser and its videos leave the rotation.")
      active: Boolean)
  final case class SetActiveResponse(
      @description("The advertiser changed.")
      id: String,
      @description("Its state after the change.")
      active: Boolean,
      @description(
        "Videos that went out of rotation with it, or came back. Reported because the blast radius is otherwise only visible in the portal.")
      activeVideos: Int)

  given Schema[AdvertiserView] = DeriveSchema.gen
  given Schema[ListRequest] = DeriveSchema.gen
  given Schema[ListResponse] = DeriveSchema.gen
  given Schema[CreateRequest] = DeriveSchema.gen
  given Schema[CreateResponse] = DeriveSchema.gen
  given Schema[UpdateRequest] = DeriveSchema.gen
  given Schema[UpdateResponse] = DeriveSchema.gen
  given Schema[SetActiveRequest] = DeriveSchema.gen
  given Schema[SetActiveResponse] = DeriveSchema.gen

  private inline def byIdQuery = quote: (idParam: String) =>
    query[AdvertiserRow].filter(_.id == idParam)

  private inline def byNameQuery = quote: (nameParam: String) =>
    query[AdvertiserRow].filter(_.name == nameParam)

  private def find(id: String): ZIO[QuillSqlite, String, AdvertiserRow] = ZIO.serviceWithZIO[
    QuillSqlite]: quill =>
    import quill.*
    run(byIdQuery(lift(id)))
      .orDie
      .map(_.headOption)
      .someOrFail(s"No advertiser with id '$id'. Call list_advertisers to see what exists.")

  /** Borrowed from [[VideoTools]], which owns the video table's queries.
    *
    * It lived here, as a `.size` over `AdvertiserVideoRow`. Harmless until the row started carrying
    * the `VideoType` enum: Quill derives the row's decoder while expanding the macro even for a
    * count, so the query needed `schema.core.given` — and scalafix then stripped that import as
    * unused, because nothing in the source references it. Keeping the query where the decoders
    * already are costs nothing and leaves no import to defend.
    */
  private def countActiveVideos(id: String): ZIO[QuillSqlite, Nothing, Int] = VideoTools
    .countActiveFor(id)

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
    .readOnly
    .handle: (in: ListRequest) =>
      ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        for
          rows <- run(query[AdvertiserRow]).orDie
          visible = rows.filter(r => in.includeInactive || r.isActive == 1).sortBy(_.name)
          views <-
            ZIO.foreach(visible): row =>
              countActiveVideos(row.id).map: videos =>
                AdvertiserView(row.id, row.name, row.priority, row.isActive == 1, videos)
        yield ListResponse(views)

  val create: McpToolHandlerR[QuillSqlite] = McpTool("create_advertiser")
    .description(
      "Register an advertiser. Priority weights the video rotation against the other active advertisers; zero means never drawn. Names must be unique. Call read_skill('add-advertiser') first.")
    .requireScopes(Auth.ContentWrite)
    .handle: (in: CreateRequest) =>
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
        yield CreateResponse(id, name, priority)

  val update: McpToolHandlerR[QuillSqlite] = McpTool("update_advertiser")
    .description("Change an advertiser's name or rotation priority. Omitted fields are left alone.")
    .requireScopes(Auth.ContentWrite)
    .handle: (in: UpdateRequest) =>
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
        yield UpdateResponse(in.id, name, priority)
        end for

  val setActive: McpToolHandlerR[QuillSqlite] = McpTool("set_advertiser_active")
    .description(
      "Retire an advertiser or bring it back. Retiring also takes its videos out of the rotation, since the video query joins on the advertiser being active. See read_skill('add-advertiser').")
    .requireScopes(Auth.ContentWrite)
    .handle: (in: SetActiveRequest) =>
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
        yield SetActiveResponse(in.id, in.active, videos)

  val all: List[McpToolHandlerR[QuillSqlite]] = List(list, create, update, setActive)
end AdvertiserTools
