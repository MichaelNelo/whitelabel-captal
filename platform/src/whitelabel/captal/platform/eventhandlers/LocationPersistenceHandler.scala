package whitelabel.captal.platform.eventhandlers

import java.time.Instant

import io.getquill.*
import whitelabel.captal.core.application.Event
import whitelabel.captal.core.device.Mac
import whitelabel.captal.core.location.{ControllerId, Event as LocationEvent, LocationId}
import whitelabel.captal.core.operation.RunId
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.{
  AccessPointRow,
  AccessPoints,
  ActiveAccessPointRow,
  ActiveAccessPoints,
  LocationAccessPointRow,
  LocationRow,
  LocationStates,
  UnifiControllerRow
}
import zio.*

/** Writes what the location's commands decided: the location, its settings, the access points it is
  * configured with and the controllers they answer to, the state each operation that completed left
  * it in, and the access points it took or let go with it — deployed or resumed, the ones its run
  * took as it started are held; paused or destroyed, let go.
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
            ensureAccessPoint(quill, point.mac, point.controller.id, at) *>
              run(
                query[LocationAccessPointRow].insertValue(
                  lift(
                    LocationAccessPointRow(
                      locationId = location.asString,
                      accessPointId = AccessPoints.idOf(point.mac),
                      createdAt = at,
                      updatedAt = at,
                      durationMinutes = point.settings.durationMinutes,
                      redirectUrl = point.settings.redirectUrl,
                      unifiIsEnabled = flag(point.settings.unifiEnabled)
                    )))) *>
              // A deployed location takes an access point it is configured with at once.
              deployedBy(quill, location).flatMap(
                ZIO.foreachDiscard(_)(by =>
                  take(quill, point.mac, location, ActiveAccessPoints.Deployed, by, at)))
          case Event.Location(LocationEvent.AccessPointUpdated(location, mac, point, occurredAt)) =>
            val at = occurredAt.toString
            val settings =
              run(
                query[LocationAccessPointRow]
                  .filter(row =>
                    row.locationId == lift(location.asString) &&
                      row.accessPointId == lift(AccessPoints.idOf(point.mac)))
                  .update(
                    _.durationMinutes -> lift(point.settings.durationMinutes),
                    _.redirectUrl     -> lift(point.settings.redirectUrl),
                    _.unifiIsEnabled  -> lift(flag(point.settings.unifiEnabled)),
                    _.updatedAt       -> lift(at)
                  )).unit
            if point.mac == mac then
              answersTo(quill, point.mac, point.controller.id, at) *> settings
            else
              // Configured with the access point of the new MAC in place of the old one: taken in
              // its place when the old one was.
              for
                held <- takenFor(quill, mac, location)
                _    <- ensureAccessPoint(quill, point.mac, point.controller.id, at)
                _    <- answersTo(quill, point.mac, point.controller.id, at)
                _    <- letGo(quill, mac, location)
                _    <- run(
                  query[LocationAccessPointRow]
                    .filter(row =>
                      row.locationId == lift(location.asString) &&
                        row.accessPointId == lift(AccessPoints.idOf(mac)))
                    .delete)
                _ <- run(
                  query[LocationAccessPointRow].insertValue(
                    lift(
                      LocationAccessPointRow(
                        locationId = location.asString,
                        accessPointId = AccessPoints.idOf(point.mac),
                        createdAt = at,
                        updatedAt = at,
                        durationMinutes = point.settings.durationMinutes,
                        redirectUrl = point.settings.redirectUrl,
                        unifiIsEnabled = flag(point.settings.unifiEnabled)
                      ))))
                _ <-
                  ZIO.foreachDiscard(held)(row =>
                    take(quill, point.mac, location, row.state, RunId(row.runId), at))
              yield ()
            end if
          case Event.Location(LocationEvent.AccessPointRemoved(location, mac, _)) =>
            letGo(quill, mac, location) *>
              run(
                query[LocationAccessPointRow]
                  .filter(row =>
                    row.locationId == lift(location.asString) &&
                      row.accessPointId == lift(AccessPoints.idOf(mac)))
                  .delete).unit
          case Event.Location(LocationEvent.ControllerRegistered(controller, occurredAt)) =>
            val at = occurredAt.toString
            run(
              query[UnifiControllerRow].insertValue(
                lift(
                  UnifiControllerRow(
                    id = controller.id.asString,
                    host = controller.host,
                    port = controller.port,
                    siteId = controller.siteId,
                    apiToken = controller.token.reveal,
                    createdAt = at,
                    updatedAt = at
                  )))).unit
          case Event.Location(LocationEvent.ControllerChanged(controller, occurredAt)) =>
            run(
              query[UnifiControllerRow]
                .filter(_.id == lift(controller.id.asString))
                .update(
                  _.host      -> lift(controller.host),
                  _.port      -> lift(controller.port),
                  _.siteId    -> lift(controller.siteId),
                  _.apiToken  -> lift(controller.token.reveal),
                  _.updatedAt -> lift(occurredAt.toString)
                )).unit
          case Event.Location(LocationEvent.ControllerRemoved(id, _)) =>
            // The command found no location configured with an access point that answers to it:
            // those that do go with it.
            run(
              query[AccessPointRow]
                .filter(point =>
                  point.controllerId == lift(id.asString) &&
                    query[LocationAccessPointRow].filter(_.accessPointId == point.id).isEmpty)
                .delete) *>
              run(query[UnifiControllerRow].filter(_.id == lift(id.asString)).delete).unit
          case Event.Location(LocationEvent.LocationDeployed(id, by, at)) =>
            record(quill, id, LocationStates.Deployed, by, at) *> hold(quill, id, at)
          case Event.Location(LocationEvent.LocationPaused(id, by, at)) =>
            record(quill, id, LocationStates.Paused, by, at) *> letGoAll(quill, id)
          case Event.Location(LocationEvent.LocationResumed(id, by, at)) =>
            record(quill, id, LocationStates.Deployed, by, at) *> hold(quill, id, at)
          case Event.Location(LocationEvent.LocationDestroyed(id, by, at)) =>
            record(quill, id, LocationStates.Destroyed, by, at) *> letGoAll(quill, id)
          case _ =>
            ZIO.unit
      end handle

  /** The access point `mac`, made the system's when it is not, answering to `controller`. Ignored
    * when it is there: the command read which controller it answers to, and a read inside the
    * transaction would not see a write the same transaction made.
    */
  private def ensureAccessPoint(
      quill: QuillSqlite,
      mac: Mac,
      controller: ControllerId,
      at: String): Task[Unit] =
    import quill.*
    run(
      query[AccessPointRow]
        .insertValue(
          lift(
            AccessPointRow(
              id = AccessPoints.idOf(mac),
              mac = mac.show,
              controllerId = controller.asString,
              createdAt = at,
              updatedAt = at)))
        .onConflictIgnore).unit

  /** The access point `mac` answering to `controller` from now on, for every location. */
  private def answersTo(
      quill: QuillSqlite,
      mac: Mac,
      controller: ControllerId,
      at: String): Task[Unit] =
    import quill.*
    run(
      query[AccessPointRow]
        .filter(row =>
          row.id == lift(AccessPoints.idOf(mac)) && row.controllerId != lift(controller.asString))
        .update(_.controllerId -> lift(controller.asString), _.updatedAt -> lift(at))).unit

  /** The run that left the location deployed, when it is. */
  private def deployedBy(quill: QuillSqlite, location: LocationId): Task[Option[RunId]] =
    import quill.*
    run(
      query[LocationRow].filter(row =>
        row.id == lift(location.asString) && row.state == lift(LocationStates.Deployed))).map(
      _.headOption.flatMap(_.stateRun).map(RunId(_)))

  /** The row by which the location has taken the access point `mac`, if it has. */
  private def takenFor(
      quill: QuillSqlite,
      mac: Mac,
      location: LocationId): Task[Option[ActiveAccessPointRow]] =
    import quill.*
    run(
      query[ActiveAccessPointRow].filter(row =>
        row.accessPointId == lift(AccessPoints.idOf(mac)) &&
          row.locationId == lift(location.asString))).map(_.headOption)

  /** The location takes the access point `mac`; a commit that finds another location took it fails
    * on the table's primary key, as a run's start does
    * ([[whitelabel.captal.platform.operations.AccessPointTaking]]).
    */
  private def take(
      quill: QuillSqlite,
      mac: Mac,
      location: LocationId,
      state: String,
      by: RunId,
      at: String): Task[Unit] =
    import quill.*
    run(
      query[ActiveAccessPointRow].insertValue(
        lift(
          ActiveAccessPointRow(
            accessPointId = AccessPoints.idOf(mac),
            locationId = location.asString,
            state = state,
            runId = by.asString,
            since = at)))).unit

  private def letGo(quill: QuillSqlite, mac: Mac, location: LocationId): Task[Unit] =
    import quill.*
    run(
      query[ActiveAccessPointRow]
        .filter(row =>
          row.accessPointId == lift(AccessPoints.idOf(mac)) &&
            row.locationId == lift(location.asString))
        .delete).unit

  private def letGoAll(quill: QuillSqlite, location: LocationId): Task[Unit] =
    import quill.*
    run(query[ActiveAccessPointRow].filter(_.locationId == lift(location.asString)).delete).unit

  /** The access points the location took as its run started, held now that the run succeeded. */
  private def hold(quill: QuillSqlite, location: LocationId, at: Instant): Task[Unit] =
    import quill.*
    run(
      query[ActiveAccessPointRow]
        .filter(_.locationId == lift(location.asString))
        .update(_.state -> lift(ActiveAccessPoints.Deployed), _.since -> lift(at.toString))).unit

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
