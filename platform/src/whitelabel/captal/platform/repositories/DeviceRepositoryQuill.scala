package whitelabel.captal.platform.repositories

import java.time.Instant

import io.getquill.*
import whitelabel.captal.core.device.{Device, State}
import whitelabel.captal.core.infrastructure.DeviceRepository
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.core.given
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.session.SessionContext
import whitelabel.captal.platform.{DeviceRow, Terms}
import zio.*

/** The session's device, read against the terms of service in force: accepted only when the version
  * it accepted last is that one.
  */
object DeviceRepositoryQuill:
  def apply(quill: QuillSqlite, ctx: SessionContext): DeviceRepository[Task] =
    new DeviceRepository[Task]:
      import quill.*

      /** The device's row and the version in force. */
      private def loaded =
        (
          for
            session <- ctx.getOrFail
            rows    <- run(query[DeviceRow].filter(_.id == lift(session.deviceId)))
            current <- Terms.current(quill)
          yield (rows.headOption, current)
        ).orDie

      def findAccepted(): Task[Option[Device[State.Accepted]]] = loaded.map: (row, current) =>
        for
          device   <- row
          terms    <- current
          accepted <- device.termsId.filter(_ == terms.id)
          at       <- device.termsAcceptedAt
        yield Device(device.id, State.Accepted(accepted, Instant.parse(at)))

      def findNotAccepted(): Task[Option[Device[State.NotAccepted.type]]] = loaded.map:
        (row, current) =>
          row
            .filterNot(device => current.exists(terms => device.termsId.contains(terms.id)))
            .map(device => Device(device.id, State.NotAccepted))

  val layer: ZLayer[QuillSqlite & SessionContext, Nothing, DeviceRepository[Task]] = ZLayer
    .fromFunction(apply)
end DeviceRepositoryQuill
