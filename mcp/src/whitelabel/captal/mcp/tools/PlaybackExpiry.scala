package whitelabel.captal.mcp.tools

import java.time.Instant

import io.getquill.*
import whitelabel.captal.core.video.Playback
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.core.given
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.{
  LocationRecovery,
  SessionRow,
  VideoFileRow,
  VideoFiles,
  VideoPlaybackRow
}
import zio.*

/** Closes the playbacks whose page never said they ended — the captive-portal browser closed, the
  * phone left the WiFi, the tab died before `pagehide` — so the file they hold can go.
  *
  * One fiber per playback, started when the change feed reports it inserted and, at startup or
  * after a gap in the feed, for every playback still open: timers live in memory, so a restart
  * would otherwise forget the ones it had. Each sleeps until [[Playback.expiresAt]] and then closes
  * the playback if it is still open. The deadline is read from the database, never from the change
  * set, and an id that names no open playback schedules nothing.
  *
  * A playback that ends any other way — the page's beacon, the watched report — has its timer
  * cancelled when the feed reports the update, rather than left asleep until a deadline that would
  * find nothing to close. Either way the timer leaves the list as its fiber ends.
  */
object PlaybackExpiry:

  /** @param start
    *   starts a playback's timer, once however often it is asked: rqlite delivers at least once.
    * @param cancel
    *   stops it, for a playback that ended; nothing for one with no timer.
    * @param pending
    *   the playbacks with a timer running.
    * @param resync
    *   a timer for every playback the database has open and for none other, as at startup: for when
    *   changes may have been missed.
    */
  final case class Timers(
      start: String => UIO[Unit],
      cancel: String => UIO[Unit],
      pending: UIO[Set[String]],
      resync: UIO[Unit])

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
      resync =
        for
          open <- openPlaybacks.provideEnvironment(env)
          ran  <- running.get.map(_.keySet)
          _    <- ZIO.foreachDiscard(ran -- open)(cancel)
          _    <- ZIO.foreachDiscard(open)(start)
        yield ()
      _ <- resync
    yield Timers(start, cancel, running.get.map(_.keySet), resync)

  private def openPlaybacks: URIO[QuillSqlite, Set[String]] = ZIO.serviceWithZIO[QuillSqlite]:
    quill =>
      import quill.*
      run(query[VideoPlaybackRow].filter(_.endedAt.isEmpty).map(_.id)).orDie.map(_.toSet)

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

  /** When an open playback is due, from its start, its file's length and the grace of the location
    * its session is at. `None` once it has ended or for an id that is no playback at all.
    */
  private def deadlineOf(id: String): URIO[QuillSqlite, Option[Instant]] = ZIO.serviceWithZIO[
    QuillSqlite]: quill =>
    import quill.*
    for
      found <-
        run(
          query[VideoPlaybackRow]
            .filter(p => p.id == lift(id) && p.endedAt.isEmpty)
            .join(query[VideoFileRow])
            .on(_.fileId == _.id)
            .leftJoin(query[SessionRow])
            .on((pf, s) => pf._1.sessionId == s.id)
            .map((pf, s) => (pf._1.startedAt, pf._2.durationSeconds, s.flatMap(_.locationId))))
          .orDie
      deadline <-
        ZIO.foreach(found.headOption): (startedAt, duration, locationId) =>
          ZIO
            .foreach(locationId)(LocationRecovery.find(quill, _))
            .orDie
            .map(row =>
              Playback.expiresAt(
                Instant.parse(startedAt),
                duration.getOrElse(0),
                LocationRecovery.grace(row.flatten)))
    yield deadline
    end for
end PlaybackExpiry
