package whitelabel.captal.core.operation

import java.time.{Duration, Instant}

import scala.collection.mutable.ListBuffer

import cats.Id
import cats.data.NonEmptyList
import cats.syntax.foldable.*
import utest.*
import whitelabel.captal.core.Op
import whitelabel.captal.core.application.Error as AppError
import whitelabel.captal.core.application.Event as AppEvent
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.device.Mac
import whitelabel.captal.core.infrastructure.{
  AccessPointRepository,
  LocationRepository,
  OperationSteps,
  RunProgress,
  RunRepository,
  TermsRepository,
  TermsVersion
}
import whitelabel.captal.core.location.{
  AccessPoint,
  Controller,
  Error as LocationError,
  Event as LocationEvent,
  Location,
  LocationId,
  Secret,
  Settings,
  Slug,
  State
}

object OperationTests extends TestSuite:
  private val Now = Instant.parse("2026-11-15T12:00:00Z")
  private val Here = Slug.unsafe("plaza")
  private val Elsewhere = Slug.unsafe("brasa")
  private val Id = LocationId("plaza-id")
  private val Ap = Mac.parse("1c:6a:1b:ee:5c:be").get
  private val NewRun = RunId("run-new")
  private val Operator = "operator-token-1"

  private val Point = AccessPoint(
    Ap,
    Controller("unifi.example", Some(8443), "default", Secret.parse("token").get),
    Settings.Default)

  private def at(state: State): Location[State] = Location(Id, Here, "Plaza", true, state)

  private val Deployed = State.Deployed(RunId("run-0"), NonEmptyList.one(Point))

  private def run(
      operation: Operation,
      state: RunState,
      pending: List[Step] = Nil,
      id: RunId = RunId("run-1")): Run[RunState] = Run(
    id,
    Id,
    Here,
    operation,
    pending,
    Operator,
    state)

  private def starting(
      location: Option[Location[State]] = Some(at(State.Created)),
      latest: Option[Run[RunState]] = None,
      points: List[AccessPoint] = List(Point),
      holders: Map[Mac, List[Slug]] = Map.empty,
      terms: Option[TermsVersion] = Some(TermsVersion("terms-1", 1))) = StartOperationHandler[Id](
    new LocationRepository[Id]:
      def find(slug: Slug) = location.filter(_.slug == slug)
      def byId(id: LocationId) = location.filter(_.id == id)
      def findDeployed(id: LocationId) = None
    ,
    new AccessPointRepository[Id]:
      def of(location: LocationId) = points
      def deployedHolders(mac: Mac) = holders.getOrElse(mac, Nil)
    ,
    runs(latest),
    new TermsRepository[Id]:
      def current = terms
  )

  private def runs(last: Option[Run[RunState]]): RunRepository[Id] =
    new RunRepository[Id]:
      def latest(location: LocationId) = last
      def findStarted(id: RunId) = last.collect:
        case found @ Run(_, _, _, _, _, _, started: RunState.Started) if found.id == id =>
          found.copy(state = started)

  /** By default the lock was taken for the new run: nobody else holds it. */
  private def start(
      operation: Operation,
      finishing: Option[RunId] = None,
      holder: Option[RunId] = None) = StartOperationCommand(
    Here,
    operation,
    finishing,
    NewRun,
    holder,
    Operator,
    Now)

  /** The events emitted, or the location errors refused with. */
  private def outcome[A](
      op: Op[AppEvent, AppError, A]): Either[List[LocationError], List[AppEvent]] =
    Op.run(op) match
      case Right((events, _)) =>
        Right(events)
      case Left(errors) =>
        Left(
          errors
            .toList
            .flatMap:
              case AppError.Location(found) =>
                found.toList
              case _ =>
                Nil)

  private def started(
      operation: Operation,
      steps: List[Step],
      finishing: Option[RunId] = None,
      supersedes: Option[RunId] = None) = Right(
    List(
      AppEvent.Operation(
        Event.OperationStarted(
          NewRun,
          Id,
          Here,
          operation,
          steps,
          finishing,
          supersedes,
          Operator,
          Now))))

  val tests = Tests:
    test("StartOperation"):
      test("refuses a slug no location has"):
        val refused = starting(location = None).handle(start(Operation.Deploy))
        assert(outcome(refused) == Left(List(LocationError.NotFound(Here))))

      test("deploys a created location with every step of a deploy"):
        assert(
          outcome(starting().handle(start(Operation.Deploy))) ==
            started(Operation.Deploy, Operation.steps(Operation.Deploy).toList))

      test("refuses to deploy a location already deployed"):
        val refused = starting(location = Some(at(Deployed))).handle(start(Operation.Deploy))
        assert(outcome(refused) == Left(List(LocationError.AlreadyDeployed(Here))))

      test("refuses an operation from a state it does not leave"):
        val refused = starting(location = Some(at(Deployed))).handle(start(Operation.Resume))
        assert(
          outcome(refused) ==
            Left(List(LocationError.NotAllowedFrom("deployed", Operation.Resume))))

      test("refuses a deploy with no terms and no access point, naming both"):
        val refused = starting(terms = None, points = Nil).handle(start(Operation.Deploy))
        assert(
          outcome(refused) ==
            Left(List(LocationError.NoTermsOfService, LocationError.NoAccessPoint)))

      test("refuses a resume through an access point another deployed location holds"):
        val refused = starting(
          location = Some(at(State.Paused(RunId("run-0")))),
          holders = Map(Ap -> List(Here, Elsewhere))).handle(start(Operation.Resume))
        assert(outcome(refused) == Left(List(LocationError.AccessPointHeld(Ap, Elsewhere))))

      test("pauses with no terms in force: a pause takes no guests"):
        val pausing = starting(location = Some(at(Deployed)), terms = None, points = Nil)
        assert(
          outcome(pausing.handle(start(Operation.Pause))) ==
            started(Operation.Pause, Operation.steps(Operation.Pause).toList))

      test("refuses while another run holds the lock, naming where it is"):
        val going = run(
          Operation.Pause,
          RunState.Started(Now.minusSeconds(5)),
          List(Step.ScaleToZero, Step.AwaitDrained))
        val refused = starting(location = Some(at(Deployed)), latest = Some(going)).handle(
          start(Operation.Pause, holder = Some(going.id)))
        assert(
          outcome(refused) ==
            Left(
              List(
                LocationError.OperationInProgress(
                  going.id,
                  Operation.Pause,
                  Some(Step.ScaleToZero)))))

      test("refuses while a run it has no record of holds the lock"):
        val holder = RunId("run-elsewhere")
        val refused = starting(location = Some(at(Deployed))).handle(
          start(Operation.Pause, holder = Some(holder)))
        assert(
          outcome(refused) ==
            Left(List(LocationError.OperationInProgress(holder, Operation.Pause, None))))

      // The lock is this command's, so nobody is at that run, whatever its heartbeat says.
      test("refuses while a run is left part way, naming what it left"):
        val left = List(Step.ScaleToZero, Step.AwaitDrained)
        val stale = run(Operation.Pause, RunState.Started(Now.minusSeconds(5)), left)
        val refused = starting(location = Some(at(Deployed)), latest = Some(stale)).handle(
          start(Operation.Pause))
        assert(
          outcome(refused) ==
            Left(List(LocationError.PreviousOperationUnfinished(stale.id, Operation.Pause, left))))

      test("a failed run is left part way too"):
        val failed = run(
          Operation.Deploy,
          RunState.Failed(Step.AwaitRevisionRunning, StepFailure.TimedOut("no task")),
          List(Step.AwaitRevisionRunning, Step.AwaitRevisionSettled)
        )
        val refused = starting(latest = Some(failed)).handle(start(Operation.Deploy))
        assert(
          outcome(refused) ==
            Left(
              List(
                LocationError.PreviousOperationUnfinished(
                  failed.id,
                  Operation.Deploy,
                  failed.pending))))

      test("finishes the run it names, with only the steps it left"):
        val left = List(Step.ScaleToZero, Step.AwaitDrained)
        val stale = run(Operation.Pause, RunState.Started(Now.minus(Duration.ofHours(1))), left)
        val finishing = starting(location = Some(at(Deployed)), latest = Some(stale)).handle(
          start(Operation.Pause, finishing = Some(stale.id)))
        // And takes over its lock, which its dead runner never released.
        assert(outcome(finishing) == started(Operation.Pause, left, Some(stale.id), Some(stale.id)))

      test("a failed run left the lock free, so finishing it supersedes nothing"):
        val failed = run(
          Operation.Deploy,
          RunState.Failed(Step.AwaitRevisionRunning, StepFailure.TimedOut("no task")),
          List(Step.AwaitRevisionRunning, Step.AwaitRevisionSettled)
        )
        val finishing = starting(latest = Some(failed)).handle(
          start(Operation.Deploy, finishing = Some(failed.id)))
        assert(
          outcome(finishing) == started(Operation.Deploy, failed.pending, Some(failed.id), None))

      // A deploy that failed part way leaves the location created, with resources in AWS.
      test("destroys from any state, a created location included"):
        val steps = List(Step.ScaleToZero, Step.AwaitDrained, Step.Teardown, Step.DeleteAssets)
        assert(
          outcome(starting().handle(start(Operation.Destroy(deleteAssets = true)))) ==
            started(Operation.Destroy(true), steps),
          outcome(
            starting(location = Some(at(State.Destroyed(RunId("run-0"))))).handle(
              start(Operation.Destroy(deleteAssets = true)))) ==
            started(Operation.Destroy(true), steps)
        )

      test("destroys over a run left part way, which it removes along with the rest"):
        val stale = run(
          Operation.Pause,
          RunState.Started(Now.minus(Duration.ofHours(1))),
          List(Step.ScaleToZero))
        val destroying = starting(location = Some(at(Deployed)), latest = Some(stale)).handle(
          start(Operation.Destroy(deleteAssets = false)))
        assert(
          outcome(destroying) ==
            started(
              Operation.Destroy(false),
              List(Step.ScaleToZero, Step.AwaitDrained, Step.Teardown, Step.KeepAssets),
              supersedes = Some(stale.id)))

    test("CompleteOperation"):
      val going = run(Operation.Pause, RunState.Started(Now), id = RunId("run-1"))

      test("moves the location to the state the operation leads to, and completes the run"):
        val done = CompleteOperationHandler[Id](runs(Some(going))).handle(
          CompleteOperationCommand(going.id, Outcome.Succeeded, Now))
        assert(
          outcome(done) ==
            Right(
              List(
                AppEvent.Location(LocationEvent.LocationPaused(Id, going.id, Now)),
                AppEvent.Operation(Event.OperationCompleted(going.id, Id, Now)))))

      test("leaves the location where it was when a step failed"):
        val failure = StepFailure.Rejected("no such service")
        val failed = CompleteOperationHandler[Id](runs(Some(going))).handle(
          CompleteOperationCommand(going.id, Outcome.Failed(Step.ScaleToZero, failure), Now))
        assert(
          outcome(failed) ==
            Right(
              List(
                AppEvent.Operation(
                  Event.OperationFailed(going.id, Id, Step.ScaleToZero, failure, Now)))))

      test("refuses a run that is not going"):
        val refused = CompleteOperationHandler[Id](runs(None)).handle(
          CompleteOperationCommand(going.id, Outcome.Succeeded, Now))
        assert(outcome(refused) == Left(List(LocationError.RunNotStarted(going.id))))

    test("Execution"):
      // The page before the API: an API that refuses behind a page that still offers the flow
      // strands the guest.
      test("a pause swaps the page and waits for the edge before the API stops"):
        val steps = Operation.steps(Operation.Pause).toList
        assert(
          steps.indexOf(Step.MaintenancePage) < steps.indexOf(Step.AnswerPaused),
          steps.indexOf(Step.AwaitEdgeDropsPage) < steps.indexOf(Step.AnswerPaused),
          steps.indexOf(Step.AnswerPaused) < steps.indexOf(Step.ScaleToZero)
        )

      test("runs the pending steps in order, recording each, and stops at the first failure"):
        val ran = ListBuffer.empty[Step]
        val recorded = ListBuffer.empty[(Step, Either[StepFailure, Option[String]])]
        val failure = StepFailure.TimedOut("still stopping")
        val steps =
          new OperationSteps[Id]:
            def run(slug: Slug, step: Step) =
              ran += step
              if step == Step.ScaleToZero then
                Left(failure)
              else
                Right(None)
        val progress =
          new RunProgress[Id]:
            def started(run: RunId, step: Step) = ()
            def finished(run: RunId, step: Step, result: Either[StepFailure, Option[String]]) =
              recorded += step -> result
            def heartbeat(run: RunId, at: Instant) = ()
        // `new`, since a case's `apply` answers the enum's type rather than the case's.
        val going = Run(
          RunId("run-1"),
          Id,
          Here,
          Operation.Pause,
          List(Step.AnswerPaused, Step.ScaleToZero, Step.AwaitDrained),
          Operator,
          new RunState.Started(Now))
        val ended = Execution.run[Id](going, steps, progress)
        assert(
          ended == Outcome.Failed(Step.ScaleToZero, failure),
          ran.toList == List(Step.AnswerPaused, Step.ScaleToZero),
          recorded.toList ==
            List(Step.AnswerPaused -> Right(None), Step.ScaleToZero -> Left(failure))
        )
end OperationTests
