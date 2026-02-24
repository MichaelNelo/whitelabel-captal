package whitelabel.captal.infra

import whitelabel.captal.core.infrastructure.SessionData
import zio.*

trait SessionContext:
  def get: UIO[Option[SessionData]]
  def getOrFail: Task[SessionData]
  def set(data: SessionData): UIO[Unit]

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

  def get: ZIO[SessionContext, Nothing, Option[SessionData]] = ZIO.serviceWithZIO(_.get)

  def getOrFail: ZIO[SessionContext, Throwable, SessionData] = ZIO.serviceWithZIO(_.getOrFail)

  def set(data: SessionData): ZIO[SessionContext, Nothing, Unit] = ZIO.serviceWithZIO(_.set(data))
end SessionContext
