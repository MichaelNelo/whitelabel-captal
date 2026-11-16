package whitelabel.captal.core.recovery

import java.time.{Duration, Instant}

import cats.Id
import cats.syntax.foldable.*
import utest.*
import whitelabel.captal.core.Op
import whitelabel.captal.core.application.Error as AppError
import whitelabel.captal.core.application.Event as AppEvent
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.infrastructure.{LocationRepository, RecoveryPolicyRepository}
import whitelabel.captal.core.location.{Error as LocationError, Location, LocationId, Slug, State}

object RecoveryPolicyTests extends TestSuite:
  private val Now: Instant = Instant.EPOCH
  private val Here = Slug.unsafe("plaza")
  private val Id = LocationId("plaza-id")

  private def changing(
      stored: PolicyOverrides = PolicyOverrides(),
      location: Option[Location[State]] = Some(
        Location(Id, Here, "Plaza", fingerprintRequired = true, State.Created))) =
    ChangeRecoveryPolicyHandler[Id](
      new LocationRepository[Id]:
        def find(slug: Slug) = location.filter(_.slug == slug)
        def byId(id: LocationId) = location.filter(_.id == id)
        def findDeployed(id: LocationId) = None
      ,
      new RecoveryPolicyRepository[Id]:
        def of(location: LocationId) = stored
    )

  private def change(overrides: PolicyOverrides, reset: Set[PolicySetting] = Set.empty) =
    ChangeRecoveryPolicyCommand(Here, overrides, reset, Now)

  /** The settings the location ends with, or the recovery errors refused with. */
  private def outcome(
      op: Op[AppEvent, AppError, PolicyOverrides]): Either[List[Any], PolicyOverrides] =
    Op.run(op) match
      case Right((events, result)) =>
        assert(events == List(AppEvent.Recovery(Event.PolicyChanged(Id, result, Now))))
        Right(result)
      case Left(errors) =>
        Left(
          errors
            .toList
            .flatMap:
              case AppError.Recovery(found) =>
                found.toList
              case AppError.Location(found) =>
                found.toList
              case other =>
                List(other))

  val tests = Tests:
    test("ChangeRecoveryPolicy"):
      test("keeps what the change does not name"):
        val stored = PolicyOverrides(maximum = Some(Duration.ofSeconds(60)))
        val result = outcome(
          changing(stored).handle(change(PolicyOverrides(recurrentFactor = Some(0.5)))))
        assert(
          result ==
            Right(
              PolicyOverrides(maximum = Some(Duration.ofSeconds(60)), recurrentFactor = Some(0.5))))

      test("a reset returns a setting to its default"):
        val stored = PolicyOverrides(
          maximum = Some(Duration.ofSeconds(60)),
          recurrentFactor = Some(0.5))
        val result = outcome(
          changing(stored).handle(change(PolicyOverrides(), Set(PolicySetting.Maximum))))
        assert(result == Right(PolicyOverrides(recurrentFactor = Some(0.5))))

      test("refuses a change that names nothing"):
        assert(
          outcome(changing().handle(change(PolicyOverrides()))) ==
            Left(List(Error.NothingToChange)))

      test("refuses every way a change makes no sense, at once"):
        val refused = changing().handle(
          change(
            PolicyOverrides(
              maximum = Some(Duration.ofSeconds(4000)),
              videoWeight = Some(-1.0),
              emailWeight = Some(0.1)),
            Set(PolicySetting.EmailWeight)))
        assert(
          outcome(refused) ==
            Left(
              List(
                Error.SetAndReset(List(PolicySetting.EmailWeight)),
                Error.Negative(List(PolicySetting.VideoWeight)),
                Error.CeilingBelowMaximum(WaitPolicy.Default.ceiling, Duration.ofSeconds(4000))
              )))

      test("refuses a location that does not exist"):
        assert(
          outcome(
            changing(location = None).handle(change(PolicyOverrides(videoWeight = Some(1))))) ==
            Left(List(LocationError.NotFound(Here))))

    test("PolicyOverrides"):
      test("what it leaves out is the default"):
        val overrides = PolicyOverrides(maximum = Some(Duration.ofSeconds(60)))
        assert(
          overrides.policy == WaitPolicy.Default.copy(maximum = Duration.ofSeconds(60)),
          overrides.grace == whitelabel.captal.core.video.Playback.Grace,
          overrides.set == List(PolicySetting.Maximum)
        )
end RecoveryPolicyTests
