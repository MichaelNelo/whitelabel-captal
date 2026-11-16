package whitelabel.captal.platform.eventhandlers

import java.time.Instant
import java.util.UUID

import io.getquill.*
import whitelabel.captal.core.application.Event
import whitelabel.captal.core.location.{Event as LocationEvent, LocationId}
import whitelabel.captal.core.operation.RunId
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.{AccessPointRow, LocationRow, LocationStates}
import zio.*

/** Writes what the location's commands decided: the location, its settings, its access points, and
  * the state each operation that completed left it in.
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
          case Event.Location(
                LocationEvent.FingerprintRequirementChanged(id, required, occurredAt)) =>
            run(
              query[LocationRow]
                .filter(_.id == lift(id.asString))
                .update(
                  _.fingerprintIsRequired -> lift(flag(required)),
                  _.updatedAt             -> lift(occurredAt.toString))).unit
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
                    updatedAt = at,
                    durationMinutes = point.settings.durationMinutes,
                    redirectUrl = point.settings.redirectUrl,
                    unifiIsEnabled = flag(point.settings.unifiEnabled)
                  )))).unit
          case Event.Location(LocationEvent.AccessPointUpdated(location, mac, point, occurredAt)) =>
            run(
              query[AccessPointRow]
                .filter(row =>
                  row.locationId == lift(location.asString) && row.mac == lift(mac.show))
                .update(
                  _.mac             -> lift(point.mac.show),
                  _.unifiHost       -> lift(point.controller.host),
                  _.unifiPort       -> lift(point.controller.port),
                  _.unifiSiteId     -> lift(point.controller.siteId),
                  _.unifiApiToken   -> lift(point.controller.token.reveal),
                  _.durationMinutes -> lift(point.settings.durationMinutes),
                  _.redirectUrl     -> lift(point.settings.redirectUrl),
                  _.unifiIsEnabled  -> lift(flag(point.settings.unifiEnabled)),
                  _.updatedAt       -> lift(occurredAt.toString)
                )).unit
          case Event.Location(LocationEvent.AccessPointRemoved(location, mac, _)) =>
            run(
              query[AccessPointRow]
                .filter(row =>
                  row.locationId == lift(location.asString) && row.mac == lift(mac.show))
                .delete).unit
          case Event.Location(LocationEvent.LocationDeployed(id, by, at)) =>
            record(quill, id, LocationStates.Deployed, by, at)
          case Event.Location(LocationEvent.LocationPaused(id, by, at)) =>
            record(quill, id, LocationStates.Paused, by, at)
          case Event.Location(LocationEvent.LocationResumed(id, by, at)) =>
            record(quill, id, LocationStates.Deployed, by, at)
          case Event.Location(LocationEvent.LocationDestroyed(id, by, at)) =>
            record(quill, id, LocationStates.Destroyed, by, at)
          case _ =>
            ZIO.unit
      end handle

  /** Where the run `by` left the location, as `locations.state` writes it. Only a run that kept its
    * location's lock to its last step ends done (the backoffice's `OperationRunner`), so no other
    * operation moved the location meanwhile.
    */
  private def record(
      quill: QuillSqlite,
      id: LocationId,
      state: String,
      by: RunId,
      at: Instant): Task[Unit] =
    import quill.*
    run(
      query[LocationRow]
        .filter(_.id == lift(id.asString))
        .update(
          _.state     -> lift(state),
          _.stateRun  -> lift(Option(by.asString)),
          _.updatedAt -> lift(at.toString))).unit

  private def flag(on: Boolean): Int =
    if on then
      1
    else
      0
end LocationPersistenceHandler
