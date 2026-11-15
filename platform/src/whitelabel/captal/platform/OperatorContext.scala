package whitelabel.captal.platform

import zio.*

/** Who an administrative flow runs for: `actor`, the operator token's id, which `event_log.actor`
  * records, and `ask`, a yes-or-no question put to that operator — `None` when nobody is on the
  * other end to answer, as when an operation's runner completes it minutes after the call that
  * started it. An answer of `None` is no answer: declined in silence, or a host that cannot ask.
  */
final case class Operator(actor: String, ask: Option[String => UIO[Option[Boolean]]])

/** What [[SessionContext]] is to a guest's flows, for an operator's: the backoffice's flows are
  * composed once, and what changes from one call to the next — who calls — is published around the
  * flow a call runs ([[locally]]) and read by the handlers when they run.
  */
trait OperatorContext:
  def get: UIO[Option[Operator]]
  def getOrFail: Task[Operator]

  /** Runs `zio` with `operator` in context and restores the previous value on exit, including when
    * the effect fails or is interrupted.
    */
  def locally[R, E, A](operator: Operator)(zio: ZIO[R, E, A]): ZIO[R, E, A]

object OperatorContext:
  /** A flow run with nobody published: its events would be logged under no one. */
  case object NotSet extends Exception("OperatorContext not set")

  val make: ULayer[OperatorContext] = ZLayer.scoped:
    FiberRef
      .make[Option[Operator]](None)
      .map: ref =>
        new OperatorContext:
          def get: UIO[Option[Operator]] = ref.get
          def getOrFail: Task[Operator] = ref.get.someOrFail(NotSet)

          def locally[R, E, A](operator: Operator)(zio: ZIO[R, E, A]): ZIO[R, E, A] =
            ref.locally(Some(operator))(zio)
end OperatorContext
