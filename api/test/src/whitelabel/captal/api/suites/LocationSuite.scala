package whitelabel.captal.api.suites

import java.time.Instant

import cats.syntax.foldable.*
import whitelabel.captal.api.TestHelpers.*
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.application.{Error as AppError, Event, Flow}
import whitelabel.captal.core.device.Mac
import whitelabel.captal.core.location.{
  ControllerChoice,
  ControllerId,
  Error as LocationError,
  Event as LocationEvent,
  Location,
  LocationId,
  Secret,
  Settings,
  SettingsChange,
  Slug,
  State
}
import whitelabel.captal.core.operation.{
  Event as OperationEvent,
  Operation,
  RunId,
  Step,
  StepFailure
}
import whitelabel.captal.platform.{Operator as Calling, OperatorContext}
import whitelabel.captal.platform.eventhandlers.{
  EventLogHandler,
  LocationPersistenceHandler,
  TransactionalEventHandler
}
import whitelabel.captal.platform.operations.AccessPointTaking
import whitelabel.captal.platform.repositories.LocationRepositoryQuill
import whitelabel.captal.platform.schema.QuillSqlite
import zio.interop.catz.*
import zio.test.*
import zio.{Task, ZIO}

/** The location's commands against the database, through the handler chain the backoffice runs them
  * with: what they write is what the repository reads back, and the rules that read the database —
  * a slug taken, a controller in use — refuse before anything is written. Its runs' starts and ends
  * go through the handlers that take and let go of access points, with nothing in AWS: two starts
  * through one access point meet on the database, and the second is refused.
  */
object LocationSuite:
  private val Now = Instant.parse("2026-11-14T12:00:00Z")
  private val Plaza = Slug.unsafe("plaza")
  private val Brasa = Slug.unsafe("brasa")
  private val Ap = Mac.parse("1c:6a:1b:ee:5c:be").get
  private val OtherAp = Mac.parse("aa:aa:aa:aa:aa:aa").get
  private val Operator = "operator-token-1"

  /** Always the one operator, as a backoffice call publishes it. */
  private val operators =
    new OperatorContext:
      def get = ZIO.some(Calling(Operator, None))
      def getOrFail = ZIO.succeed(Calling(Operator, None))
      def locally[R, E, A](operator: Calling)(zio: ZIO[R, E, A]): ZIO[R, E, A] = zio

  /** A new controller at `host`, its id named after it. */
  private def at(host: String, token: String = "token") = ControllerChoice.New(
    ControllerId(s"controller-$host"),
    host,
    Some(8443),
    "default",
    Secret.parse(token).get)

  final private class Commands(quill: QuillSqlite):
    val repo = LocationRepositoryQuill(quill)
    val controllers = LocationRepositoryQuill.controllers(quill)
    private val events = TransactionalEventHandler(
      LocationPersistenceHandler().andThen(EventLogHandler.forOperator(operators)),
      quill)
    private val creating = Flow(CreateLocationHandler[Task](repo), events)
    private val registering = Flow(
      RegisterAccessPointHandler[Task](repo, repo, controllers),
      events)
    private val updating = Flow(UpdateAccessPointHandler[Task](repo, repo, controllers), events)
    private val removing = Flow(RemoveAccessPointHandler[Task](repo, repo), events)
    private val requiring = Flow(RequireFingerprintHandler[Task](repo), events)
    private val changing = Flow(UpdateControllerHandler[Task](controllers), events)
    private val retiring = Flow(RemoveControllerHandler[Task](controllers), events)
    private val starting =
      AccessPointTaking.refusing(repo)(
        TransactionalEventHandler(AccessPointTaking.handler(), quill))
    private val ending = TransactionalEventHandler(
      LocationPersistenceHandler().andThen(AccessPointTaking.handler()),
      quill)

    def create(slug: Slug) = creating.execute(
      CreateLocationCommand(LocationId(s"${slug.asString}-id"), slug, s"Name of $slug", Now))

    def requireFingerprint(slug: Slug, required: Boolean) = requiring.execute(
      RequireFingerprintCommand(slug, required, Now))

    def register(
        slug: Slug,
        mac: Mac,
        controller: Option[ControllerChoice] = None,
        settings: SettingsChange = SettingsChange()) = registering.execute(
      RegisterAccessPointCommand(slug, mac, controller, settings, Now))

    def update(
        slug: Slug,
        mac: Mac,
        newMac: Option[Mac] = None,
        controller: Option[ControllerId] = None,
        settings: SettingsChange = SettingsChange()) = updating.execute(
      UpdateAccessPointCommand(slug, mac, newMac, controller, settings, Now))

    def remove(slug: Slug, mac: Mac) = removing.execute(RemoveAccessPointCommand(slug, mac, Now))

    def moveController(id: ControllerId, host: String) = changing.execute(
      UpdateControllerCommand(id, Some(host), None, None, None, Now))

    def removeController(id: ControllerId) = retiring.execute(RemoveControllerCommand(id, Now))

    /** The run `run` of `operation` on the location starts: what the backoffice writes once `core`
      * let it.
      */
    def start(slug: Slug, operation: Operation, run: String) = starting.handle(
      List(
        Event.Operation(
          OperationEvent.OperationStarted(
            RunId(run),
            idOf(slug),
            slug,
            operation,
            Nil,
            None,
            Operator,
            Now))))

    /** The run `run` of `operation` succeeds, moving the location where it leads. */
    def succeed(slug: Slug, operation: Operation, run: String) = ending.handle(
      List(
        Event.Location(
          operation match
            case Operation.Deploy =>
              LocationEvent.LocationDeployed(idOf(slug), RunId(run), Now)
            case Operation.Pause =>
              LocationEvent.LocationPaused(idOf(slug), RunId(run), Now)
            case Operation.Resume =>
              LocationEvent.LocationResumed(idOf(slug), RunId(run), Now)
            case Operation.Destroy(_) =>
              LocationEvent.LocationDestroyed(idOf(slug), RunId(run), Now))))

    def fail(slug: Slug, run: String) = ending.handle(
      List(
        Event.Operation(
          OperationEvent.OperationFailed(
            RunId(run),
            idOf(slug),
            Step.Service,
            StepFailure.Rejected("refused"),
            Now))))

    def deploy(slug: Slug, run: String) =
      start(slug, Operation.Deploy, run) *> succeed(slug, Operation.Deploy, run)

    private def idOf(slug: Slug) = LocationId(s"${slug.asString}-id")
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

  private def deployedWith(found: Option[Location[State]]) = found
    .map(_.state)
    .collect { case State.Deployed(_, points) =>
      points.toList.map(_.mac)
    }

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
              Some(at("first.example")),
              SettingsChange(redirectUrl = Some(Some("https://plaza.example"))))
            // The controller moves and keeps its token.
            _ <- run.moveController(ControllerId("controller-first.example"), "second.example")
            _ <- run.update(
              Plaza,
              Ap,
              settings = SettingsChange(
                durationMinutes = Some(Some(45)),
                unifiEnabled = Some(false)))
            _      <- run.requireFingerprint(Plaza, required = false)
            found  <- run.repo.find(Plaza)
            points <- run.repo.of(created.id)
          yield assertTrue(
            found.map(_.state).contains(State.Created),
            found.exists(!_.fingerprintRequired),
            points.map(_.mac) == List(Ap),
            points.map(_.controller.host) == List("second.example"),
            points.map(_.controller.token.reveal) == List("token"),
            points.map(_.settings) ==
              List(
                Settings(
                  durationMinutes = Some(45),
                  redirectUrl = Some("https://plaza.example"),
                  unifiEnabled = false))
          )
        ,
        test("every change is logged under the operator who made it, and no token is"):
          for
            run <- commands
            _   <- run.create(Plaza)
            _   <- run.register(Plaza, Ap, Some(at("plaza.example", token = "secret-token")))
            _   <- run.remove(Plaza, Ap)
            log <- whitelabel.captal.api.TestFixtures.queryEventLog
            written = log.filter(e =>
              e.eventType.startsWith("location.") || e.eventType.startsWith("unifi."))
          yield assertTrue(
            written.map(_.eventType).toSet ==
              Set(
                "location.created",
                "unifi.controller_registered",
                "location.access_point_registered",
                "location.access_point_removed"),
            written.forall(_.actor.contains(Operator)),
            written.forall(_.sessionId.isEmpty),
            written.exists(e =>
              e.eventType == "unifi.controller_registered" &&
                e.eventData.contains("plaza.example")),
            !log.exists(_.eventData.contains("secret-token"))
          )
        ,
        test("a slug another location has is refused, and nothing is written"):
          for
            run     <- commands
            _       <- run.create(Plaza)
            refused <- refusal(run.create(Plaza))
          yield assertTrue(refused == List(LocationError.SlugTaken(Plaza, "Name of plaza")))
        ,
        test("two locations configured with one access point: the one that starts first takes it"):
          for
            run <- commands
            _   <- run.create(Plaza)
            _   <- run.create(Brasa)
            _   <- run.register(Brasa, Ap, Some(at("unifi.example")))
            // The system has the access point: Plaza is configured with it as it is.
            _       <- run.register(Plaza, Ap)
            _       <- run.start(Brasa, Operation.Deploy, "run-brasa")
            refused <- refusal(run.start(Plaza, Operation.Deploy, "run-plaza"))
            taking  <- run.repo.takenBy(Ap)
            _       <- run.succeed(Brasa, Operation.Deploy, "run-brasa")
            _       <- run.start(Brasa, Operation.Pause, "run-pause")
            // While the pause runs, Brasa still serves.
            pausing <- run.repo.takenBy(Ap)
            _       <- run.succeed(Brasa, Operation.Pause, "run-pause")
            _       <- run.deploy(Plaza, "run-plaza-again")
            took    <- run.repo.takenBy(Ap)
            plaza   <- run.repo.find(Plaza)
          yield assertTrue(
            refused == List(LocationError.AccessPointHeld(Ap, Brasa)),
            taking.contains(Brasa),
            pausing.contains(Brasa),
            took.contains(Plaza),
            deployedWith(plaza).contains(List(Ap))
          )
        ,
        test("a deploy that fails lets its access points go"):
          for
            run    <- commands
            _      <- run.create(Plaza)
            _      <- run.register(Plaza, Ap, Some(at("unifi.example")))
            _      <- run.start(Plaza, Operation.Deploy, "run-1")
            taking <- run.repo.takenBy(Ap)
            _      <- run.fail(Plaza, "run-1")
            free   <- run.repo.takenBy(Ap)
          yield assertTrue(taking.contains(Plaza), free.isEmpty)
        ,
        test("a deployed location takes an access point it is configured with at once"):
          for
            run <- commands
            _   <- run.create(Plaza)
            _   <- run.create(Brasa)
            _   <- run.register(Plaza, Ap, Some(at("unifi.example")))
            _   <- run.deploy(Plaza, "run-1")
            _   <- run.register(
              Plaza,
              OtherAp,
              Some(ControllerChoice.Existing(ControllerId("controller-unifi.example"))))
            _       <- run.register(Brasa, OtherAp)
            refused <- refusal(run.start(Brasa, Operation.Deploy, "run-2"))
            took    <- run.repo.takenBy(OtherAp)
          yield assertTrue(
            took.contains(Plaza),
            refused == List(LocationError.AccessPointHeld(OtherAp, Plaza)))
        ,
        test("a deployed location moved to another MAC takes it in place of the old one"):
          for
            run   <- commands
            _     <- run.create(Plaza)
            _     <- run.register(Plaza, Ap, Some(at("unifi.example")))
            _     <- run.deploy(Plaza, "run-1")
            _     <- run.update(Plaza, Ap, newMac = Some(OtherAp))
            old   <- run.repo.takenBy(Ap)
            moved <- run.repo.takenBy(OtherAp)
            found <- run.repo.find(Plaza)
          yield assertTrue(
            old.isEmpty,
            moved.contains(Plaza),
            deployedWith(found).contains(List(OtherAp)))
        ,
        test("a deployed location keeps its last access point"):
          for
            run     <- commands
            _       <- run.create(Plaza)
            _       <- run.register(Plaza, Ap, Some(at("plaza.example")))
            _       <- run.deploy(Plaza, "run-1")
            refused <- refusal(run.remove(Plaza, Ap))
            _       <- run.register(
              Plaza,
              OtherAp,
              Some(ControllerChoice.Existing(ControllerId("controller-plaza.example"))))
            _     <- run.remove(Plaza, Ap)
            found <- run.repo.find(Plaza)
            freed <- run.repo.takenBy(Ap)
          yield assertTrue(
            refused == List(LocationError.LastAccessPoint(Ap)),
            deployedWith(found).contains(List(OtherAp)),
            freed.isEmpty)
        ,
        test("a controller an access point answers to stays, and goes once none does"):
          val first = ControllerId("controller-first.example")
          for
            run     <- commands
            _       <- run.create(Plaza)
            _       <- run.register(Plaza, Ap, Some(at("first.example")))
            _       <- run.register(Plaza, OtherAp, Some(at("second.example")))
            refused <- refusal(run.removeController(first))
            _ <- run.update(Plaza, Ap, controller = Some(ControllerId("controller-second.example")))
            _ <- run.removeController(first)
            gone   <- run.controllers.find(first)
            points <- run.repo.of(LocationId("plaza-id"))
          yield assertTrue(
            refused == List(LocationError.ControllerInUse(first, 1)),
            gone.isEmpty,
            points.map(_.controller.host) == List("second.example", "second.example"))
        ,
        test("a controller goes with the access points no location is configured with"):
          val first = ControllerId("controller-first.example")
          for
            run <- commands
            _   <- run.create(Plaza)
            _   <- run.register(Plaza, Ap, Some(at("first.example")))
            _   <- run.register(Plaza, OtherAp, Some(ControllerChoice.Existing(first)))
            // The system keeps the access point, answering to its controller.
            _       <- run.remove(Plaza, Ap)
            kept    <- run.repo.controllerOf(Ap)
            refused <- refusal(run.removeController(first))
            _       <- run.remove(Plaza, OtherAp)
            _       <- run.removeController(first)
            gone    <- run.controllers.find(first)
            orphans <- ZIO.foreach(List(Ap, OtherAp))(run.repo.controllerOf)
          yield assertTrue(
            kept.map(_.id).contains(first),
            refused == List(LocationError.ControllerInUse(first, 1)),
            gone.isEmpty,
            orphans.forall(_.isEmpty))
      )
end LocationSuite
