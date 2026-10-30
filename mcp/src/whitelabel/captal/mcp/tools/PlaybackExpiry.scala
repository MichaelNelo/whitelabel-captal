package whitelabel.captal.mcp.tools

import java.time.Instant

import io.getquill.*
import whitelabel.captal.core.video.Playback
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.core.given
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.{VideoFileRow, VideoFiles, VideoPlaybackRow}
import zio.*

/** Closes the playbacks whose page never said they ended — the captive-portal browser closed, the
  * phone left the WiFi, the tab died before `pagehide` — so the file they hold can go.
  *
  * One fiber per playback, started when rqlite reports it inserted and, at startup, for every
  * playback still open: timers live in memory, so a restart would otherwise forget the ones it had.
  * Each sleeps until [[Playback.expiresAt]] and then closes the playback if it is still open. The
  * deadline is read from the database, never from the post: the route is not authenticated, and an
  * id that names no open playback schedules nothing.
  *
  * A playback that ends any other way — the page's beacon, the watched report — has its timer
  * cancelled when rqlite reports the update, rather than left asleep until a deadline that would
  * find nothing to close. Either way the timer leaves the list as its fiber ends.
  */
object PlaybackExpiry:

  /** @param start
    *   starts a playback's timer, once however often it is asked: rqlite delivers at least once.
    * @param cancel
    *   stops it, for a playback that ended; nothing for one with no timer.
    * @param pending
    *   the playbacks with a timer running.
    */
  final case class Timers(
      start: String => UIO[Unit],
      cancel: String => UIO[Unit],
      pending: UIO[Set[String]])

  def make(asked: Queue[Unit]): ZIO[QuillSqlite & Scope, Nothing, Timers] =
    for
      env   <- ZIO.environment[QuillSqlite]
      scope <- ZIO.scope
      // Synchronized, so a timer that ends at once cannot leave the map before it is in it: its
      // removal waits for the insertion that holds the lock.
      running <- Ref.Synchronized.make(Map.empty[String, Fiber.Runtime[Nothing, Unit]])
      start =
        (id: String) =>
          running.updateZIO: timers =>
            if timers.contains(id) then
              ZIO.succeed(timers)
            else
              expireWhenDue(id, asked)
                .ensuring(running.update(_ - id))
                .provideEnvironment(env)
                .forkIn(scope)
                .map(fiber => timers + (id -> fiber))
      // `interruptFork`, not `interrupt`: the fiber's own removal needs the lock this holds.
      cancel =
        (id: String) =>
          running.updateZIO: timers =>
            ZIO.foreachDiscard(timers.get(id))(_.interruptFork).as(timers - id)
      open <- ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        run(query[VideoPlaybackRow].filter(_.endedAt.isEmpty).map(_.id)).orDie
      _ <- ZIO.foreachDiscard(open)(start)
    yield Timers(start, cancel, running.get.map(_.keySet))

  private def expireWhenDue(id: String, asked: Queue[Unit]): URIO[QuillSqlite, Unit] = deadlineOf(
    id).flatMap(ZIO.foreachDiscard(_)(expireAt(id, _, asked)))

  /** Closes it at `deadline` if it is still open then, and asks for a pass when it was. */
  private def expireAt(id: String, deadline: Instant, asked: Queue[Unit]): URIO[QuillSqlite, Unit] =
    for
      now     <- Clock.instant
      _       <- ZIO.when(deadline.isAfter(now))(ZIO.sleep(Duration.fromInterval(now, deadline)))
      at      <- Clock.instant
      expired <- ZIO.serviceWithZIO[QuillSqlite](quill => VideoFiles.expire(quill, id, at).orDie)
      _       <-
        ZIO.when(expired)(
          ZIO.logInfo(s"Playback $id expired: its page never said it ended") *> asked.offer(()))
    yield ()

  /** When an open playback is due, from its start and its file's length. `None` once it has ended
    * or for an id that is no playback at all.
    */
  private def deadlineOf(id: String): URIO[QuillSqlite, Option[Instant]] = ZIO
    .serviceWithZIO[QuillSqlite]: quill =>
      import quill.*
      run(
        query[VideoPlaybackRow]
          .filter(p => p.id == lift(id) && p.endedAt.isEmpty)
          .join(query[VideoFileRow])
          .on(_.fileId == _.id)
          .map((p, f) => (p.startedAt, f.durationSeconds))).orDie
    .map(
      _.headOption
        .map((startedAt, duration) =>
          Playback.expiresAt(Instant.parse(startedAt), duration.getOrElse(0))))
end PlaybackExpiry
