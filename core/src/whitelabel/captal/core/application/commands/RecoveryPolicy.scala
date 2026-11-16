package whitelabel.captal.core.application.commands

import java.time.Instant

import cats.Monad
import cats.data.NonEmptyChain
import cats.syntax.functor.*
import whitelabel.captal.core.Op as CoreOp
import whitelabel.captal.core.application.{Error, Event}
import whitelabel.captal.core.infrastructure.{LocationRepository, RecoveryPolicyRepository}
import whitelabel.captal.core.location.Slug
import whitelabel.captal.core.recovery.{
  Error as RecoveryError,
  Event as RecoveryEvent,
  PolicyOverrides,
  PolicySetting
}

/** Changes the recovery settings of the location `slug`: `change` sets what it names, `reset`
  * returns those settings to their default, and the rest are kept.
  */
final case class ChangeRecoveryPolicyCommand(
    slug: Slug,
    change: PolicyOverrides,
    reset: Set[PolicySetting],
    occurredAt: Instant)

/** The location's settings after the change, whole. Refused when it names nothing, sets and resets
  * one setting at once, sets one below zero, or leaves the ceiling below the maximum, which no wait
  * could then reach. Applies to the next guest who asks for a way out and the next playback that
  * starts; a wait already offered keeps its length.
  */
object ChangeRecoveryPolicyHandler:
  def apply[F[_]: Monad](locations: LocationRepository[F], policies: RecoveryPolicyRepository[F])
      : Handler.Aux[F, ChangeRecoveryPolicyCommand, PolicyOverrides] =
    new Handler[F, ChangeRecoveryPolicyCommand]:
      type Result = PolicyOverrides

      def handle(cmd: ChangeRecoveryPolicyCommand) =
        Decided.located(locations, cmd.slug): location =>
          policies
            .of(location.id)
            .map: stored =>
              val next = stored.updated(cmd.change).without(cmd.reset)
              val policy = next.policy
              val both = cmd.change.set.filter(cmd.reset.contains)
              val negative = cmd.change.negative
              val refusals =
                if cmd.change.set.isEmpty && cmd.reset.isEmpty then
                  List(RecoveryError.NothingToChange)
                else
                  List(
                    Option.when(both.nonEmpty)(RecoveryError.SetAndReset(both)),
                    Option.when(negative.nonEmpty)(RecoveryError.Negative(negative)),
                    Option.when(policy.ceiling.compareTo(policy.maximum) < 0)(
                      RecoveryError.CeilingBelowMaximum(policy.ceiling, policy.maximum))
                  ).flatten
              NonEmptyChain.fromSeq(refusals) match
                case Some(errors) =>
                  CoreOp.fail[Event, Error, PolicyOverrides](Error.Recovery(errors))
                case None =>
                  CoreOp.emit[Event, Error, PolicyOverrides](
                    Event.Recovery(RecoveryEvent.PolicyChanged(location.id, next, cmd.occurredAt)),
                    next)
end ChangeRecoveryPolicyHandler
