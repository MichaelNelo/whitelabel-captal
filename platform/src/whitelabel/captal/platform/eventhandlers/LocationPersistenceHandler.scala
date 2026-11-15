package whitelabel.captal.platform.eventhandlers

import java.util.UUID

import io.getquill.*
import whitelabel.captal.core.application.Event
import whitelabel.captal.core.location.Event as LocationEvent
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.{AccessPointRow, LocationRow}
import zio.*

/** Writes what the location's commands decided: the location, its settings, its access points.
  *
  * Inside the transaction the rqlite driver defers every write to the commit and reports none of
  * them affecting a row, so no count is checked here. What a count would catch is checked before:
  * the commands read that the slug is free and that the access point is there before deciding, and
  * a statement rqlite refuses fails the commit.
  */
object LocationPersistenceHandler:
  def apply(): DbEventHandler[Event] =
    new DbEventHandler[Event]:
      def handle(events: List[Event], quill: QuillSqlite): Task[Unit] =
        import quill.*
        ZIO.foreachDiscard(events):
          case Event.Location(LocationEvent.LocationCreated(id, slug, name, occurredAt)) =>
            val at = occurredAt.toString
            run(
              query[LocationRow].insertValue(
                lift(
                  LocationRow(
                    id = id.asString,
                    slug = slug.asString,
                    name = name,
                    isActive = 1,
                    createdAt = at,
                    updatedAt = at)))).unit
          case Event.Location(LocationEvent.SettingsChanged(id, settings, occurredAt)) =>
            run(
              query[LocationRow]
                .filter(_.id == lift(id.asString))
                .update(
                  _.unifiDurationMinutes  -> lift(settings.durationMinutes),
                  _.unifiRedirectUrl      -> lift(settings.redirectUrl),
                  _.unifiIsEnabled        -> lift(flag(settings.unifiEnabled)),
                  _.fingerprintIsRequired -> lift(flag(settings.fingerprintRequired)),
                  _.updatedAt             -> lift(occurredAt.toString)
                )).unit
          case Event.Location(LocationEvent.AccessPointRegistered(location, point, occurredAt)) =>
            val at = occurredAt.toString
            run(
              query[AccessPointRow].insertValue(
                lift(
                  AccessPointRow(
                    id = UUID.randomUUID.toString,
                    locationId = location.asString,
                    mac = point.mac.show,
                    unifiHost = point.controller.host,
                    unifiPort = point.controller.port,
                    unifiSiteId = point.controller.siteId,
                    unifiApiToken = point.controller.token.reveal,
                    createdAt = at,
                    updatedAt = at
                  )))).unit
          case Event.Location(LocationEvent.AccessPointUpdated(location, mac, point, occurredAt)) =>
            run(
              query[AccessPointRow]
                .filter(row =>
                  row.locationId == lift(location.asString) && row.mac == lift(mac.show))
                .update(
                  _.mac           -> lift(point.mac.show),
                  _.unifiHost     -> lift(point.controller.host),
                  _.unifiPort     -> lift(point.controller.port),
                  _.unifiSiteId   -> lift(point.controller.siteId),
                  _.unifiApiToken -> lift(point.controller.token.reveal),
                  _.updatedAt     -> lift(occurredAt.toString)
                )).unit
          case Event.Location(LocationEvent.AccessPointRemoved(location, mac, _)) =>
            run(
              query[AccessPointRow]
                .filter(row =>
                  row.locationId == lift(location.asString) && row.mac == lift(mac.show))
                .delete).unit
          case _ =>
            ZIO.unit
      end handle

  private def flag(on: Boolean): Int =
    if on then
      1
    else
      0
end LocationPersistenceHandler
