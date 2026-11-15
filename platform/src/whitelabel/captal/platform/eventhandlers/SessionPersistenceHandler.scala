package whitelabel.captal.platform.eventhandlers

import io.getquill.*
import whitelabel.captal.core.application.Event
import whitelabel.captal.core.session.Event as SessionEvent
import whitelabel.captal.platform.SessionRow
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.core.given
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.session.SessionService
import zio.*

/** Writes what entering the portal decided of a session: the one it started, and the welcome of one
  * it resumed. The session is the one the event names: there is none in the context yet.
  */
object SessionPersistenceHandler:
  def apply(): DbEventHandler[Event] =
    new DbEventHandler[Event]:
      def handle(events: List[Event], quill: QuillSqlite): Task[Unit] =
        import quill.*
        ZIO.foreachDiscard(events):
          case Event.Session(started: SessionEvent.SessionStarted) =>
            run(
              query[SessionRow].insert(
                _.id          -> lift(started.sessionId),
                _.userId      -> lift(started.userId),
                _.deviceId    -> lift(started.deviceId),
                _.locale      -> lift(started.locale),
                _.phase       -> lift(started.phase),
                _.locationId  -> lift(started.locationId),
                _.createdAt   -> lift(started.occurredAt.toString),
                _.clientMac   -> lift(Option(started.clientMac)),
                _.apMac       -> lift(started.apMac),
                _.redirectUrl -> lift(started.redirectUrl),
                _.ssid        -> lift(started.ssid),
                _.fingerprint -> lift(started.fingerprint)
              )).unit
          case Event.Session(SessionEvent.WelcomeResolved(sessionId, phase, _)) =>
            run(SessionService.updatePhaseQuery(lift(sessionId), lift(phase))).unit
          case _ =>
            ZIO.unit
      end handle
end SessionPersistenceHandler
