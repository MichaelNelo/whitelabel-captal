package whitelabel.captal.api.suites

import java.time.Instant

import cats.syntax.foldable.*
import whitelabel.captal.api.TestHelpers.*
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.application.{Error as AppError, Flow}
import whitelabel.captal.core.device.Mac
import whitelabel.captal.core.location.{
  Error as LocationError,
  LocationId,
  Secret,
  Settings,
  SettingsChange,
  Slug,
  State
}
import whitelabel.captal.platform.LocationStates
import whitelabel.captal.platform.eventhandlers.{
  LocationPersistenceHandler,
  TransactionalEventHandler
}
import whitelabel.captal.platform.repositories.LocationRepositoryQuill
import whitelabel.captal.platform.schema.QuillSqlite
import zio.interop.catz.*
import zio.test.*
import zio.{Task, ZIO}

/** The location's commands against the database, through the handler chain the backoffice runs them
  * with: what they write is what the repository reads back, and the rules that read the database —
  * a slug taken, an access point another deployed location holds — refuse before anything is
  * written.
  */
object LocationSuite:
  private val Now = Instant.parse("2026-11-14T12:00:00Z")
  private val Plaza = Slug.unsafe("plaza")
  private val Brasa = Slug.unsafe("brasa")
  private val Ap = Mac.parse("1c:6a:1b:ee:5c:be").get
  private val OtherAp = Mac.parse("aa:aa:aa:aa:aa:aa").get

  final private class Commands(quill: QuillSqlite):
    val repo = LocationRepositoryQuill(quill)
    private val events = TransactionalEventHandler(LocationPersistenceHandler(), quill)
    private val creating = Flow(CreateLocationHandler[Task](repo), events)
    private val registering = Flow(RegisterAccessPointHandler[Task](repo, repo), events)
    private val updating = Flow(UpdateAccessPointHandler[Task](repo, repo), events)
    private val removing = Flow(RemoveAccessPointHandler[Task](repo, repo), events)

    def create(slug: Slug) = creating.execute(
      CreateLocationCommand(LocationId(s"${slug.asString}-id"), slug, s"Name of $slug", Now))

    def register(
        slug: Slug,
        mac: Mac,
        host: String,
        token: Option[String] = Some("token"),
        settings: SettingsChange = SettingsChange()) = registering.execute(
      RegisterAccessPointCommand(
        slug,
        mac,
        host,
        Some(8443),
        "default",
        token.flatMap(Secret.parse),
        settings,
        Now))

    def update(slug: Slug, mac: Mac, settings: SettingsChange) = updating.execute(
      UpdateAccessPointCommand(slug, mac, None, None, None, None, settings, Now))

    def remove(slug: Slug, mac: Mac) = removing.execute(RemoveAccessPointCommand(slug, mac, Now))
  end Commands

  private val commands = ZIO.serviceWith[QuillSqlite](Commands(_))

  /** The location errors a command was refused with; none when it was accepted. */
  private def refusal[A](command: Task[A]): Task[List[LocationError]] = command
    .either
    .map:
      case Left(Flow.HandlerError(errors)) =>
        errors
          .toList
          .flatMap:
            case AppError.Location(found) =>
              found.toList
            case _ =>
              Nil
      case _ =>
        Nil

  private def deploy(slug: Slug) = ZIO.serviceWithZIO[QuillSqlite](
    LocationStates.record(_, slug.asString, LocationStates.Deployed, "run-1"))

  val suite: Spec[TestEnv, Throwable] =
    zio
      .test
      .suite("Locations")(
        test("what the commands write is what the repository reads back"):
          for
            run     <- commands
            created <- run.create(Plaza)
            _       <- run.register(
              Plaza,
              Ap,
              "first.example",
              settings = SettingsChange(redirectUrl = Some(Some("https://plaza.example"))))
            // The same MAC again, with no token: the controller changes and keeps its token.
            _ <- run.register(Plaza, Ap, "second.example", token = None)
            _ <- run.update(
              Plaza,
              Ap,
              SettingsChange(durationMinutes = Some(Some(45)), fingerprintRequired = Some(false)))
            found  <- run.repo.find(Plaza)
            points <- run.repo.of(created.id)
          yield assertTrue(
            found.map(_.state).contains(State.Created),
            points.map(_.mac) == List(Ap),
            points.map(_.controller.host) == List("second.example"),
            points.map(_.controller.token.reveal) == List("token"),
            points.map(_.settings) ==
              List(
                Settings
                  .Default
                  .copy(
                    durationMinutes = Some(45),
                    redirectUrl = Some("https://plaza.example"),
                    fingerprintRequired = false))
          )
        ,
        test("a slug another location has is refused, and nothing is written"):
          for
            run     <- commands
            _       <- run.create(Plaza)
            refused <- refusal(run.create(Plaza))
          yield assertTrue(refused == List(LocationError.SlugTaken(Plaza, "Name of plaza")))
        ,
        test("an access point another deployed location takes guests through is refused"):
          for
            run      <- commands
            _        <- run.create(Plaza)
            _        <- run.create(Brasa)
            _        <- run.register(Brasa, Ap, "brasa.example")
            free     <- run.register(Plaza, Ap, "plaza.example").either
            _        <- run.remove(Plaza, Ap)
            _        <- deploy(Brasa)
            holders  <- run.repo.deployedHolders(Ap)
            refused  <- refusal(run.register(Plaza, Ap, "plaza.example"))
            deployed <- run.repo.find(Brasa)
          yield assertTrue(
            // While Brasa was not deployed, the access point was free to take.
            free.isRight,
            holders == List(Brasa),
            refused == List(LocationError.AccessPointHeld(Ap, Brasa)),
            deployed
              .map(_.state)
              .exists {
                case State.Deployed(_, points) =>
                  points.toList.map(_.mac) == List(Ap)
                case _ =>
                  false
              }
          )
        ,
        test("a deployed location keeps its last access point"):
          for
            run     <- commands
            _       <- run.create(Plaza)
            _       <- run.register(Plaza, Ap, "plaza.example")
            _       <- deploy(Plaza)
            refused <- refusal(run.remove(Plaza, Ap))
            _       <- run.register(Plaza, OtherAp, "plaza.example")
            _       <- run.remove(Plaza, Ap)
            found   <- run.repo.find(Plaza)
          yield assertTrue(
            refused == List(LocationError.LastAccessPoint(Ap)),
            found
              .map(_.state)
              .exists {
                case State.Deployed(_, points) =>
                  points.toList.map(_.mac) == List(OtherAp)
                case _ =>
                  false
              }
          )
      )
end LocationSuite
