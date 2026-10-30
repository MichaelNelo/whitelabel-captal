package whitelabel.captal.core.access

import java.time.Instant

import cats.Id
import utest.*
import whitelabel.captal.core.application.commands.{
  ExpireAccessCommand,
  ExpireAccessHandler,
  FinishCommand,
  FinishHandler
}
import whitelabel.captal.core.application.{Error as AppError, Event as AppEvent, Phase}
import whitelabel.captal.core.infrastructure.{SessionData, UserRepository}
import whitelabel.captal.core.{Op, user}

object AccessTests extends TestSuite:
  private val Now = Instant.parse("2026-10-31T12:00:00Z")
  private val Session = user.SessionId.generate

  private def session(phase: Phase, userId: Option[user.Id], expiresAt: Option[Instant]) =
    SessionData(
      Session,
      user.DeviceId.generate,
      userId,
      "es",
      phase,
      None,
      None,
      accessExpiresAt = expiresAt)

  private def expiring(s: SessionData) =
    Op.run(ExpireAccessHandler.withSession[Id](s).handle(ExpireAccessCommand(Now))).toOption.get._1

  /** Only `findReadyUser` is ever asked by `finish`. */
  private def users(ready: Option[user.User[user.State.Ready]]): UserRepository[Id] =
    new UserRepository[Id]:
      def findWithEmail() = None
      def findIdByEmail(email: user.Email) = None
      def findAnswering() = None
      def findWatchingVideo() = None
      def findAnsweringVideoSurvey() = None
      def findReadyUser() = ready

  private def finishing(s: SessionData, ready: Option[user.User[user.State.Ready]] = None) = Op.run(
    FinishHandler.withSession[Id](users(ready), s).handle(FinishCommand(Now)))

  val tests = Tests:
    test("FinishHandler"):
      val someone = user.Id.generate
      val readyUser = user.User[user.State.Ready](
        someone,
        user.State.Ready("http://google.com", None, Nil))
      test("out of an error, access is granted with no user"):
        assert(
          finishing(session(Phase.Recovered, None, None)).toOption.get._1 ==
            List(AppEvent.Access(Event.AccessGranted(Session, None, Now))))
      test("out of an error after giving an email, granted with that user, the flow unfinished"):
        assert(
          finishing(session(Phase.Recovered, Some(someone), None), Some(readyUser))
            .toOption
            .get
            ._1 == List(AppEvent.Access(Event.AccessGranted(Session, Some(someone), Now))))
      test("at the end of the flow, the user finishes it and access is granted"):
        val events =
          finishing(session(Phase.Ready, Some(someone), None), Some(readyUser)).toOption.get._1
        assert(
          events
            .collect { case AppEvent.User(_: user.Event.UserFinishedProcess) =>
              ()
            }
            .size == 1,
          events.last == AppEvent.Access(Event.AccessGranted(Session, Some(someone), Now))
        )
      test("at the end of the flow with no user, nobody to finish it"):
        assert(
          finishing(session(Phase.Ready, None, None))
            .left
            .exists(_.exists(_ == AppError.UserNotIdentified)))

    test("ExpireAccessHandler"):
      test("an authorized session past its expiry expires, with or without a user"):
        val someone = user.Id.generate
        assert(
          expiring(session(Phase.Authorized, Some(someone), Some(Now))) ==
            List(AppEvent.Access(Event.AccessExpired(Session, Some(someone), Now))),
          expiring(session(Phase.Authorized, None, Some(Now.minusSeconds(1)))) ==
            List(AppEvent.Access(Event.AccessExpired(Session, None, Now)))
        )
      test("a session no longer authorized is a stale job"):
        assert(expiring(session(Phase.Welcome, None, None)).isEmpty)
      test("a session authorized again until later is a stale job"):
        assert(expiring(session(Phase.Authorized, None, Some(Now.plusSeconds(600)))).isEmpty)
end AccessTests
