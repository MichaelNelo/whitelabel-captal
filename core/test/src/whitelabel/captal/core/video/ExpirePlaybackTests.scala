package whitelabel.captal.core.video

import java.time.{Duration, Instant}
import java.util.UUID

import cats.Id as Same
import utest.*
import whitelabel.captal.core.Op
import whitelabel.captal.core.application.Event as AppEvent
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.infrastructure.{OpenPlayback, PlaybackRepository}
import whitelabel.captal.core.user

object ExpirePlaybackTests extends TestSuite:
  private val Started: Instant = Instant.EPOCH
  private val Session = user.SessionId(UUID(0, 1))

  /** A 30-second file and a minute of grace: due at 90 seconds. */
  private val Open = OpenPlayback(
    PlaybackId(UUID(0, 2)),
    Session,
    Started,
    fileDurationSeconds = 30,
    grace = Duration.ofMinutes(1))

  private val playbacks =
    new PlaybackRepository[Same]:
      def open(id: PlaybackId) = Option.when(id == Open.id)(Open)

  private def expire(id: PlaybackId, at: Instant) = Op.run(
    ExpirePlaybackHandler[Same](playbacks).handle(ExpirePlaybackCommand(id, at)))

  val tests = Tests:
    test("closes an open playback once it is due, and only then"):
      val due = Started.plusSeconds(90)
      assert(
        expire(Open.id, due) ==
          Right((List(AppEvent.Video(Event.PlaybackExpired(Open.id, Session, due))), true)),
        expire(Open.id, due.minusSeconds(1)) == Right((Nil, false))
      )

    test("leaves alone one that ended or never was"):
      assert(expire(PlaybackId(UUID(0, 9)), Started.plusSeconds(600)) == Right((Nil, false)))
end ExpirePlaybackTests
