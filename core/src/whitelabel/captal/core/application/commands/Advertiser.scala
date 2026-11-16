package whitelabel.captal.core.application.commands

import java.time.Instant

import cats.Monad
import cats.data.NonEmptyChain
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import whitelabel.captal.core.Op as CoreOp
import whitelabel.captal.core.advertiser.{
  Advertiser,
  Error as AdvertiserError,
  Event as AdvertiserEvent
}
import whitelabel.captal.core.application.{Error, Event}
import whitelabel.captal.core.infrastructure.AdvertiserRepository
import whitelabel.captal.core.survey.AdvertiserId

final case class CreateAdvertiserCommand(
    id: AdvertiserId,
    name: String,
    priority: Int,
    occurredAt: Instant)

/** The new advertiser, active, its name trimmed. Refused for a blank name, a priority below zero,
  * and a name another advertiser has.
  */
object CreateAdvertiserHandler:
  def apply[F[_]: Monad](
      advertisers: AdvertiserRepository[F]): Handler.Aux[F, CreateAdvertiserCommand, Advertiser] =
    new Handler[F, CreateAdvertiserCommand]:
      type Result = Advertiser

      def handle(cmd: CreateAdvertiserCommand) =
        val name = cmd.name.trim
        advertisers
          .named(name)
          .map: same =>
            val created = Advertiser(cmd.id, name, cmd.priority, active = true)
            decidedAdvertiser(
              List(
                Option.when(name.isEmpty)(AdvertiserError.BlankName),
                Option.when(cmd.priority < 0)(AdvertiserError.NegativePriority(cmd.priority)),
                same.headOption.map(other => AdvertiserError.NameTaken(name, other.id))
              ).flatten,
              List(AdvertiserEvent.AdvertiserCreated(created, cmd.occurredAt)),
              created
            )
end CreateAdvertiserHandler

/** Changes the name, the priority, or both, of the advertiser `id`; what is left out is kept. */
final case class UpdateAdvertiserCommand(
    id: AdvertiserId,
    name: Option[String],
    priority: Option[Int],
    occurredAt: Instant)

/** The advertiser after the change. Refused when it names nothing, and as a creation is. */
object UpdateAdvertiserHandler:
  def apply[F[_]: Monad](
      advertisers: AdvertiserRepository[F]): Handler.Aux[F, UpdateAdvertiserCommand, Advertiser] =
    new Handler[F, UpdateAdvertiserCommand]:
      type Result = Advertiser

      def handle(cmd: UpdateAdvertiserCommand) =
        existing(advertisers, cmd.id): found =>
          val name = cmd.name.fold(found.name)(_.trim)
          val priority = cmd.priority.getOrElse(found.priority)
          advertisers
            .named(name)
            .map: same =>
              val updated = found.copy(name = name, priority = priority)
              decidedAdvertiser(
                if cmd.name.isEmpty && cmd.priority.isEmpty then
                  List(AdvertiserError.NothingToUpdate)
                else
                  List(
                    Option.when(name.isEmpty)(AdvertiserError.BlankName),
                    Option.when(priority < 0)(AdvertiserError.NegativePriority(priority)),
                    same
                      .find(_.id != found.id)
                      .map(other => AdvertiserError.NameTaken(name, other.id))
                  ).flatten
                ,
                List(AdvertiserEvent.AdvertiserUpdated(found.id, name, priority, cmd.occurredAt)),
                updated
              )
end UpdateAdvertiserHandler

/** Takes the advertiser `id`, and its videos with it, out of the rotation. */
final case class RetireAdvertiserCommand(id: AdvertiserId, occurredAt: Instant)

object RetireAdvertiserHandler:
  def apply[F[_]: Monad](
      advertisers: AdvertiserRepository[F]): Handler.Aux[F, RetireAdvertiserCommand, Unit] =
    new Handler[F, RetireAdvertiserCommand]:
      type Result = Unit

      def handle(cmd: RetireAdvertiserCommand) =
        existing(advertisers, cmd.id): found =>
          Monad[F].pure(
            decidedAdvertiser(
              Nil,
              List(AdvertiserEvent.AdvertiserRetired(found.id, cmd.occurredAt)),
              ()))
end RetireAdvertiserHandler

/** Brings the advertiser `id` back into the rotation. */
final case class ReinstateAdvertiserCommand(id: AdvertiserId, occurredAt: Instant)

object ReinstateAdvertiserHandler:
  def apply[F[_]: Monad](
      advertisers: AdvertiserRepository[F]): Handler.Aux[F, ReinstateAdvertiserCommand, Unit] =
    new Handler[F, ReinstateAdvertiserCommand]:
      type Result = Unit

      def handle(cmd: ReinstateAdvertiserCommand) =
        existing(advertisers, cmd.id): found =>
          Monad[F].pure(
            decidedAdvertiser(
              Nil,
              List(AdvertiserEvent.AdvertiserReinstated(found.id, cmd.occurredAt)),
              ()))
end ReinstateAdvertiserHandler

/** The advertiser `id`, or refused when there is none. */
private def existing[F[_]: Monad, A](advertisers: AdvertiserRepository[F], id: AdvertiserId)(
    decide: Advertiser => F[Op[A]]): F[Op[A]] = advertisers
  .find(id)
  .flatMap:
    case Some(found) =>
      decide(found)
    case None =>
      Monad[F].pure(
        CoreOp.fail[Event, Error, A](
          Error.Advertiser(NonEmptyChain.one(AdvertiserError.NotFound(id.asString)))))

/** Refused with `errors` when there are any; otherwise `events`, answering `result`. */
private def decidedAdvertiser[A](
    errors: List[AdvertiserError],
    events: => List[AdvertiserEvent],
    result: => A): Op[A] =
  NonEmptyChain.fromSeq(errors) match
    case Some(found) =>
      CoreOp.fail[Event, Error, A](Error.Advertiser(found))
    case None =>
      CoreOp.emitAll[Event, Error, A](events.map(Event.Advertiser(_)), result)
