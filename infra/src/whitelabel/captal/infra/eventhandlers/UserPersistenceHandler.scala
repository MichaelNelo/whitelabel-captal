package whitelabel.captal.infra.eventhandlers

import java.time.Instant

import io.getquill.*
import whitelabel.captal.core.application.Event
import whitelabel.captal.core.infrastructure.SessionData
import whitelabel.captal.core.user
import whitelabel.captal.core.user.Event as UserEvent
import whitelabel.captal.infra.schema.QuillSqlite
import whitelabel.captal.infra.schema.core.given
import whitelabel.captal.infra.schema.given
import whitelabel.captal.infra.session.SessionContext
import whitelabel.captal.infra.{SessionRow, UserRow}
import zio.*

object UserPersistenceHandler:
  inline def findByEmailQuery = quote: (emailParam: user.Email) =>
    query[UserRow].filter(_.email.contains(emailParam))

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
        val userEvents = events.collect:
          case Event.User(e: UserEvent.UserCreated) =>
            e
        if userEvents.nonEmpty then
          for
            sessionData <- ctx.getOrFail
            _           <-
              ZIO.foreachDiscard(userEvents): event =>
                for
                  existingUser <- run(findByEmailQuery(lift(event.email))).map(_.headOption)
                  resolvedUserId =
                    existingUser match
                      case Some(existingUserRow) =>
                        existingUserRow.id
                      case None =>
                        event.userId
                  _ <-
                    existingUser match
                      case Some(_) =>
                        ZIO.unit
                      case None =>
                        val userRow = toUserRow(event, sessionData)
                        run(insertUserQuery(liftQuery(List(userRow)))).unit
                  _ <- run(
                    updateSessionUserQuery(lift(sessionData.sessionId), lift(resolvedUserId)))
                  _ <- ctx.set(sessionData.copy(userId = Some(resolvedUserId)))
                yield ()
          yield ()
        else
          ZIO.unit
        end if
      end handle

  private def toUserRow(event: UserEvent.UserCreated, sessionData: SessionData): UserRow =
    val now = Instant.now.toString
    UserRow(
      id = event.userId,
      email = Some(event.email),
      locale = sessionData.locale,
      createdAt = event.occurredAt.toString,
      updatedAt = now)
end UserPersistenceHandler
