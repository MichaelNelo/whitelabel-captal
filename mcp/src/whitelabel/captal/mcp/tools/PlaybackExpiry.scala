package whitelabel.captal.mcp.tools

import java.time.Instant

import whitelabel.captal.core.application.commands.ExpirePlaybackCommand
import whitelabel.captal.core.application.queries.{OpenPlaybackReport, OpenPlaybacksQuery}
import whitelabel.captal.core.video.PlaybackId
import whitelabel.captal.platform.OperatorContext
import zio.*

/** Closes the playbacks whose page never said they ended — the captive-portal browser closed, the
  * phone left the WiFi, the tab died before `pagehide` — so the file they hold can go.
  *
  * One fiber per playback, started when the change feed reports it inserted and, at startup or
  * after a gap in the feed, for every playback still open: timers live in memory, so a restart
  * would otherwise forget the ones it had. Each sleeps until the deadline `OpenPlaybacksQuery`
  * answers and then runs `ExpirePlaybackCommand`, which closes the playback if it is still open and
  * due. The deadline is read from the database, never from the change set, and an id that names no
  * open playback schedules nothing.
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

  /** What the timers read and run: the open playbacks with their deadlines, and the command that
    * closes one, recorded under [[VideoFileCollector.Actor]].
    */
  type Needs = OperatorContext & VideoFlows.ExpirePlayback & Queries.OpenPlaybacks

  def make(asked: Queue[Unit]): ZIO[Needs & Scope, Nothing, Timers] =
    for
      env   <- ZIO.environment[Needs]
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

  /** The open playbacks, `id` alone or every one, with when each is due. A listing is not refused,
    * so a failure here is a defect.
    */
  private def open(id: Option[String]): URIO[Queries.OpenPlaybacks, List[OpenPlaybackReport]] = ZIO
    .serviceWithZIO[Queries.OpenPlaybacks](Commands.ask(_, OpenPlaybacksQuery(id))())
    .orDieWith(IllegalStateException(_))

  private def openPlaybacks: URIO[Queries.OpenPlaybacks, Set[String]] = open(None).map(
    _.map(_.id).toSet)

  /** Waits until the playback is due and closes it, asking for a pass when it did. One the command
    * finds not due yet — its location's grace lengthened meanwhile — is waited for again, until its
    * deadline; one that ended, or a deadline that would not move, ends the timer.
    */
  private def expireWhenDue(
      id: String,
      asked: Queue[Unit],
      waited: Option[Instant] = None): URIO[Needs, Unit] = open(Some(id))
    .map(_.headOption.map(_.expiresAt).filter(due => waited.forall(due.isAfter)))
    .flatMap:
      case None =>
        ZIO.unit
      case Some(deadline) =>
        for
          now <- Clock.instant
          _   <- ZIO.when(deadline.isAfter(now))(ZIO.sleep(Duration.fromInterval(now, deadline)))
          expired <- expire(id)
          _       <-
            if expired then
              ZIO.logInfo(s"Playback $id expired: its page never said it ended") *> asked.offer(())
            else
              expireWhenDue(id, asked, Some(deadline))
        yield ()

  /** Whether `ExpirePlaybackCommand` closed it. Closing is not refused, so a failure here is a
    * defect; an id that names no playback closes nothing.
    */
  private def expire(id: String): URIO[OperatorContext & VideoFlows.ExpirePlayback, Boolean] =
    PlaybackId.fromString(id) match
      case None =>
        ZIO.succeed(false)
      case Some(playback) =>
        for
          at      <- Clock.instant
          expired <- ZIO
            .serviceWithZIO[VideoFlows.ExpirePlayback](
              Commands.run(VideoFileCollector.Actor, ExpirePlaybackCommand(playback, at))(_)())
            .orDieWith(IllegalStateException(_))
        yield expired
end PlaybackExpiry
