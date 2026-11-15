package whitelabel.captal.platform.eventhandlers

import io.getquill.*
import whitelabel.captal.core.application.Event
import whitelabel.captal.core.device.Event as DeviceEvent
import whitelabel.captal.platform.DeviceRow
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.core.given
import whitelabel.captal.platform.schema.given
import zio.*

/** Writes the devices entering the portal finds: a new one, or a known one with what the policy did
  * not compare brought up to date.
  */
object DevicePersistenceHandler:
  def apply(): DbEventHandler[Event] =
    new DbEventHandler[Event]:
      def handle(events: List[Event], quill: QuillSqlite): Task[Unit] =
        import quill.*
        ZIO.foreachDiscard(events):
          case Event.Device(DeviceEvent.DeviceRegistered(id, userAgent, signals, occurredAt)) =>
            val at = occurredAt.toString
            run(
              query[DeviceRow].insertValue(
                lift(
                  DeviceRow(
                    id = id,
                    userAgent = userAgent,
                    createdAt = at,
                    updatedAt = at,
                    clientMac = signals.clientMac,
                    fingerprint = signals.fingerprint)))).unit
          case Event.Device(DeviceEvent.DeviceRecognized(id, userAgent, signals, occurredAt)) =>
            run(
              query[DeviceRow]
                .filter(_.id == lift(id))
                .update(
                  _.userAgent   -> lift(userAgent),
                  _.clientMac   -> lift(signals.clientMac),
                  _.fingerprint -> lift(signals.fingerprint),
                  _.updatedAt   -> lift(occurredAt.toString))).unit
          case _ =>
            ZIO.unit
      end handle
end DevicePersistenceHandler
