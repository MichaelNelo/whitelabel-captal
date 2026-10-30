package whitelabel.captal.platform.eventhandlers

import io.getquill.*
import whitelabel.captal.core.application.Event
import whitelabel.captal.core.device.Event as DeviceEvent
import whitelabel.captal.core.infrastructure.SessionData
import whitelabel.captal.core.user
import whitelabel.captal.core.user.Event as UserEvent
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.core.given
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.session.SessionContext
import whitelabel.captal.platform.{DeviceUserRow, SessionRow, UserRow}
import zio.*

/** The session's user, as `core` resolved it: a new one is inserted, a recognized one only becomes
  * the session's. Whether an email belongs to somebody already is `AnswerEmailHandler`'s to decide.
  * Also the pairs of a user and a device seen together.
  */
object UserPersistenceHandler:
  inline def insertUserQuery = quote: (users: Query[UserRow]) =>
    users.foreach(u =>
      query[UserRow].insert(
        _.id        -> u.id,
        _.email     -> u.email,
        _.locale    -> u.locale,
        _.createdAt -> u.createdAt,
        _.updatedAt -> u.updatedAt))

  inline def updateSessionUserQuery = quote:
    (sessionIdParam: user.SessionId, userIdParam: user.Id) =>
      query[SessionRow].filter(_.id == sessionIdParam).update(_.userId -> Some(userIdParam))

  def apply(ctx: SessionContext): DbEventHandler[Event] =
    new DbEventHandler[Event]:
      def handle(events: List[Event], quill: QuillSqlite): Task[Unit] =
        import quill.*
        val identified = events.collect:
          case Event.User(e: UserEvent.UserCreated) =>
            e.userId -> Some(e)
          case Event.User(e: UserEvent.UserRecognized) =>
            e.userId -> None
        val links = events.collect:
          case Event.Device(e: DeviceEvent.DeviceLinked) =>
            e
        ZIO.whenDiscard(identified.nonEmpty || links.nonEmpty):
          for
            sessionData <- ctx.getOrFail
            instant     <- Clock.instant
            _           <-
              ZIO.foreachDiscard(identified): (userId, created) =>
                for
                  _ <-
                    ZIO.foreachDiscard(created): event =>
                      val userRow = toUserRow(event, sessionData, instant.toString)
                      run(insertUserQuery(liftQuery(List(userRow)))).unit
                  _ <- run(updateSessionUserQuery(lift(sessionData.sessionId), lift(userId)))
                  _ <- ctx.set(sessionData.copy(userId = Some(userId)))
                yield ()
            _ <- ZIO.foreachDiscard(links)(link(quill, _))
          yield ()
      end handle

  /** Seen together again only moves `last_seen_at`. */
  private def link(quill: QuillSqlite, event: DeviceEvent.DeviceLinked): Task[Unit] =
    import quill.*
    val seenAt = event.occurredAt.toString
    val row = DeviceUserRow(event.deviceId, event.userId, seenAt, seenAt)
    run(
      query[DeviceUserRow]
        .insertValue(lift(row))
        .onConflictUpdate(_.deviceId, _.userId)((t, e) => t.lastSeenAt -> e.lastSeenAt)).unit

  private def toUserRow(
      event: UserEvent.UserCreated,
      sessionData: SessionData,
      now: String): UserRow = UserRow(
    id = event.userId,
    email = Some(event.email),
    locale = sessionData.locale,
    createdAt = event.occurredAt.toString,
    updatedAt = now)
end UserPersistenceHandler
