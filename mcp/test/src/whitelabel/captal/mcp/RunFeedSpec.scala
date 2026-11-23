package whitelabel.captal.mcp

import java.time.Instant

import whitelabel.captal.cdc.feed.ChangeFeedQueue
import whitelabel.captal.core.application.Error
import whitelabel.captal.core.application.queries.{Query, RunReport, RunStatusQuery}
import whitelabel.captal.core.operation.Run
import whitelabel.captal.mcp.apps.{RunFeed, RunRoutes}
import whitelabel.captal.mcp.tools.Queries
import whitelabel.captal.platform.operations.RunRecords
import zio.*
import zio.test.*

/** What the run page's stream sends, and when: the run as it is, then again each time the change
  * feed says its row was written, and once more when its heartbeat goes stale with nothing written.
  * Against a run held in memory and read as `RunStatus` reads it, so the clock is the test's.
  */
object RunFeedSpec extends ZIOSpecDefault:

  private val runId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
  private val otherRun = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"

  private def run(status: String, heartbeat: Instant): RunReport = RunReport(
    id = runId,
    slug = "cafe",
    operation = "deploy",
    status = status,
    steps = Nil,
    startedBy = "token",
    startedAt = Instant.EPOCH.toString,
    updatedAt = heartbeat.toString,
    finishedAt = None,
    error = None
  )

  /** `row` read as the query reads `mcp_deploy_runs`: a running run whose heartbeat is older than
    * [[Run.StaleAfter]] is interrupted.
    */
  private def query(row: Ref[RunReport]): Queries.RunStatus =
    new Query[Task, RunStatusQuery]:
      type Result = RunReport
      def execute(query: RunStatusQuery): Task[Either[Error, RunReport]] =
        for
          held <- row.get
          now  <- Clock.instant
        yield
          val stale = Instant.parse(held.updatedAt).plus(Run.StaleAfter).isBefore(now)
          Right(
            if held.status == RunRecords.Running && stale then
              held.copy(status = RunRecords.Interrupted)
            else
              held)

  /** The stream for `runId`, each event offered to the queue it answers, running until it ends. */
  private def watching(row: Ref[RunReport], feed: RunFeed): ZIO[Scope, Nothing, Queue[String]] =
    for
      events <- Queue.unbounded[String]
      _      <-
        RunRoutes
          .events(runId)
          .provideEnvironment(ZEnvironment(feed).add[Queries.RunStatus](query(row)))
          .foreach(events.offer)
          .forkScoped
    yield events

  private def changeSet(table: String, op: String, row: String, id: String): String =
    s"""{"events":[{"op":"$op","table":"$table","$row":{"id":"$id"}}]}"""

  def spec =
    suite("RunFeed")(
      test("a change set names the runs it wrote, by the row after or the row before"):
        val written = ChangeFeedQueue
          .Event
          .Changed(1, changeSet(RunFeed.Table, "UPDATE", "after", runId), Map.empty)
        val deleted = ChangeFeedQueue
          .Event
          .Changed(2, changeSet(RunFeed.Table, "DELETE", "before", otherRun), Map.empty)
        val playback = ChangeFeedQueue
          .Event
          .Changed(3, changeSet("video_playbacks", "INSERT", "after", runId), Map.empty)
        assertTrue(
          RunFeed.signalsOf(written) == List(RunFeed.Signal.Changed(runId)),
          RunFeed.signalsOf(deleted) == List(RunFeed.Signal.Changed(otherRun)),
          RunFeed.signalsOf(playback).isEmpty,
          RunFeed.signalsOf(ChangeFeedQueue.Event.Gap) == List(RunFeed.Signal.Gap)
        )
      ,
      test("the run as it is, then again when the feed names it, and not for another run's"):
        ZIO.scoped:
          for
            start  <- Clock.instant
            row    <- Ref.make(run(RunRecords.Running, start))
            feed   <- RunFeed.make
            events <- watching(row, feed)
            first  <- events.take
            // Written, but the feed names another run: nothing to send.
            _ <- row.set(run(RunRecords.Running, start.plusSeconds(10)))
            _ <- feed.publish(RunFeed.Signal.Changed(otherRun))
            // Written again, and named.
            _      <- row.set(run(RunRecords.Running, start.plusSeconds(20)))
            _      <- feed.publish(RunFeed.Signal.Changed(runId))
            second <- events.take
          yield assertTrue(
            first.contains(s""""updatedAt":"$start""""),
            second.contains(s""""updatedAt":"${start.plusSeconds(20)}""""))
      ,
      test("a gap reads the run again"):
        ZIO.scoped:
          for
            start  <- Clock.instant
            row    <- Ref.make(run(RunRecords.Running, start))
            feed   <- RunFeed.make
            events <- watching(row, feed)
            _      <- events.take
            _      <- row.set(run(RunRecords.Succeeded, start.plusSeconds(10)))
            _      <- feed.publish(RunFeed.Signal.Gap)
            last   <- events.take
          yield assertTrue(last.contains(""""status":"succeeded""""))
      ,
      test("a finished run is sent once and the stream ends"):
        for
          start <- Clock.instant
          row   <- Ref.make(run(RunRecords.Succeeded, start))
          feed  <- RunFeed.make
          sent  <-
            RunRoutes
              .events(runId)
              .provideEnvironment(ZEnvironment(feed).add[Queries.RunStatus](query(row)))
              .runCollect
        yield assertTrue(sent.size == 1, sent.head.contains(""""status":"succeeded""""))
      ,
      test("a run nothing writes any more is sent as interrupted once its heartbeat goes stale"):
        ZIO.scoped:
          for
            start  <- Clock.instant
            row    <- Ref.make(run(RunRecords.Running, start))
            feed   <- RunFeed.make
            events <- watching(row, feed)
            _      <- events.take
            // Moved on a little at a time, so the stream is asleep on its deadline before the
            // clock passes it.
            last <- (TestClock.adjust(10.seconds) *> events.poll).repeatUntil(_.isDefined)
            now  <- Clock.instant
          yield assertTrue(
            last.exists(_.contains(""""status":"interrupted"""")),
            !now.isBefore(start.plus(Run.StaleAfter)))
    )
end RunFeedSpec
