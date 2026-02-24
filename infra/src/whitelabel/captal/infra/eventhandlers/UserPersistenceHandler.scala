package whitelabel.captal.infra.eventhandlers

import java.time.Instant

import io.getquill.*
import whitelabel.captal.core.application.Event
import whitelabel.captal.core.infrastructure.SessionData
import whitelabel.captal.core.user.{Event as UserEvent, Id as UserId}
import whitelabel.captal.infra.QuillSchema.given
import whitelabel.captal.infra.{DbEventHandler, QuillSqlite, SessionContext, SessionRow, UserRow}
import zio.*

object UserPersistenceHandler:
  inline def findByEmailQuery = quote: (email: String) =>
    query[UserRow].filter(_.email.contains(email))

  inline def insertUserQuery = quote: (users: Query[UserRow]) =>
    users.foreach(u =>
      query[UserRow].insert(
        _.id        -> u.id,
        _.email     -> u.email,
        _.locale    -> u.locale,
        _.createdAt -> u.createdAt,
        _.updatedAt -> u.updatedAt))

  inline def updateSessionUserQuery = quote: (sessionId: String, userId: String) =>
    query[SessionRow].filter(_.id == sessionId).update(_.userId -> Some(userId))

  def apply(ctx: SessionContext): DbEventHandler[Event] =
    new DbEventHandler[Event]:
      def handle(events: List[Event], quill: QuillSqlite): Task[Unit] =
        import quill.*
        val userEvents = events.collect:
          case Event.User(e: UserEvent.UserCreated) =>
            e
        if userEvents.nonEmpty then
          for
            sessionData <- ctx.getOrFail
            sessionId = sessionData.sessionId.asString
            _ <- ZIO.foreachDiscard(userEvents): event =>
              for
                existingUser <- run(findByEmailQuery(lift(event.email.value))).map(_.headOption)
                resolvedUserId = existingUser match
                  case Some(user) =>
                    UserId.fromString(user.id).get
                  case None =>
                    event.userId
                _ <- existingUser match
                  case Some(_) =>
                    ZIO.unit
                  case None =>
                    val userRow = toUserRow(event, sessionData)
                    run(insertUserQuery(liftQuery(List(userRow)))).unit
                _ <- run(updateSessionUserQuery(lift(sessionId), lift(resolvedUserId.asString)))
                _ <- ctx.set(sessionData.copy(userId = Some(resolvedUserId)))
              yield ()
          yield ()
        else
          ZIO.unit

  private def toUserRow(event: UserEvent.UserCreated, sessionData: SessionData): UserRow =
    val now = Instant.now.toString
    UserRow(
      id = event.userId.asString,
      email = Some(event.email.value),
      locale = sessionData.locale,
      createdAt = event.occurredAt.toString,
      updatedAt = now)
end UserPersistenceHandler
