package whitelabel.captal.infra.session

import whitelabel.captal.core.infrastructure.SessionData
import zio.*

trait SessionContext:
  def get: UIO[Option[SessionData]]
  def getOrFail: Task[SessionData]
  def set(data: SessionData): UIO[Unit]

  /** Runs `zio` with `data` in context and restores the previous value on exit — including when the
    * effect fails or is interrupted.
    *
    * Exists to run flows **outside a request**. Within a request, `SessionEndpoint.secured` calls
    * `set` and the whole chain inherits it; the fiber dies once the response is sent, so not
    * restoring is harmless. The scheduler worker, by contrast, lives indefinitely and processes one
    * job after another, so the scope is bounded explicitly rather than leaving the previous job's
    * session sitting in the slot.
    *
    * This is what lets the scheduler reuse the entire event pipeline: repositories and every
    * `DbEventHandler` read the session from the ambient context, and this makes them see it exactly
    * as in a request without changing a single signature.
    */
  def locally[R, E, A](data: SessionData)(zio: ZIO[R, E, A]): ZIO[R, E, A]

object SessionContext:
  case object NotSet extends Exception("SessionContext not set")

  val make: ULayer[SessionContext] = ZLayer.scoped:
    FiberRef
      .make[Option[SessionData]](None)
      .map: ref =>
        new SessionContext:
          def get: UIO[Option[SessionData]] = ref.get
          def getOrFail: Task[SessionData] = ref.get.someOrFail(NotSet)
          def set(data: SessionData): UIO[Unit] = ref.set(Some(data))

          def locally[R, E, A](data: SessionData)(zio: ZIO[R, E, A]): ZIO[R, E, A] =
            ref.locally(Some(data))(zio)

  def get: ZIO[SessionContext, Nothing, Option[SessionData]] = ZIO.serviceWithZIO(_.get)

  def getOrFail: ZIO[SessionContext, Throwable, SessionData] = ZIO.serviceWithZIO(_.getOrFail)

  def set(data: SessionData): ZIO[SessionContext, Nothing, Unit] = ZIO.serviceWithZIO(_.set(data))
end SessionContext
