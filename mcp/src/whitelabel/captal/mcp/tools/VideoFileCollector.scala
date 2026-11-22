package whitelabel.captal.mcp.tools

import io.opentelemetry.api.common.{AttributeKey, Attributes}
import io.opentelemetry.api.trace.SpanKind
import whitelabel.captal.cdc.feed.ChangeFeedQueue
import whitelabel.captal.core.application.commands.CollectReplacedFilesCommand
import whitelabel.captal.core.video
import whitelabel.captal.platform.{Operator, OperatorContext}
import whitelabel.captal.telemetry.{Telemetry, Tracing}
import zio.*
import zio.stream.ZStream

/** Deletes the replaced video files nobody is playing any more, through `core`'s
  * `CollectReplacedFilesCommand`: a pass, recorded in `event_log` under [[Actor]].
  *
  * Run when a replacement is confirmed, since the previous file may have no playback left already;
  * on every change set the change feed relays, since a playback ending can be what frees one; when
  * [[PlaybackExpiry]] closes a playback its page never ended; and once at startup, for whatever
  * changed while this server was not there to hear it.
  */
object VideoFileCollector:
  /** Who the jobs' changes are recorded under: no operator asked for them. */
  private[mcp] val Actor = Operator("job:video-upkeep", None)

  /** The files deleted, of `videoId` alone or of every video. A pass is not refused, so a failure
    * here is a defect.
    */
  def collect(videoId: Option[video.Id])
      : ZIO[OperatorContext & VideoFlows.CollectReplacedFiles, Nothing, Int] =
    for
      now     <- Clock.instant
      deleted <- ZIO
        .serviceWithZIO[VideoFlows.CollectReplacedFiles](
          Commands.run(Actor, CollectReplacedFilesCommand(videoId, now))(_)())
        .orDieWith(IllegalStateException(_))
    yield deleted

  /** What the change feed drives: a collection pass, and a timer per playback that started. */
  final case class Running(asked: Queue[Unit], timers: PlaybackExpiry.Timers)

  /** What a change set says happened to a playback. */
  enum PlaybackChange:
    case Started(id: String)
    case Ended(id: String)

  /** A pass whenever asked, one at a time: posts that arrive during a pass are folded into the one
    * after it, so a burst of playbacks ending costs two passes, not one each.
    */
  def runner
      : ZIO[PlaybackExpiry.Needs & VideoFlows.CollectReplacedFiles & Scope, Nothing, Running] =
    for
      asked <- Queue.sliding[Unit](1)
      _     <-
        asked
          .take
          .zipRight(
            collect(None).tap(n =>
              ZIO.when(n > 0)(ZIO.logInfo(s"Deleted $n replaced video files"))))
          .forever
          .forkScoped
      _      <- asked.offer(())
      timers <- PlaybackExpiry.make(asked)
    yield Running(asked, timers)

  /** Follows the change feed for as long as it runs: see [[changed]]. Each change set is handled in
    * a span continuing the trace the relay received it in, so that trace shows the backoffice among
    * the subscribers it reached.
    */
  def follow(
      running: Running,
      feed: ZStream[Any, Nothing, ChangeFeedQueue.Event]): URIO[Tracing, Unit] = feed.foreach:
    case event @ ChangeFeedQueue.Event.Changed(index, _, context) =>
      Telemetry.continuing(
        context,
        "cdc VideoFileCollector",
        SpanKind.CONSUMER,
        Attributes.of(AttributeKey.longKey("cdc.change.index"), index))(changed(running, event))
    case event =>
      changed(running, event)

  /** Every change set that touches `video_playbacks` asks for a pass; a playback starting starts
    * its timer, and one ending cancels it. A change set of any other table is not this collector's.
    * A gap — change sets that may never arrive — resyncs the timers with the database, as at
    * startup, and asks for a pass too.
    */
  def changed(running: Running, event: ChangeFeedQueue.Event): UIO[Unit] =
    event match
      case ChangeFeedQueue.Event.Changed(_, json, _) =>
        ZIO
          .when(playbackEvents(json).nonEmpty)(
            ZIO
              .foreachDiscard(playbackChanges(json)):
                case PlaybackChange.Started(id) =>
                  running.timers.start(id)
                case PlaybackChange.Ended(id) =>
                  running.timers.cancel(id)
              .zipRight(running.asked.offer(())))
          .unit
      case ChangeFeedQueue.Event.Gap =>
        running.timers.resync *> running.asked.offer(()).unit

  import zio.json.ast.Json

  private def field(json: Json, name: String): Option[Json] = json.asObject.flatMap(_.get(name))

  /** The events of a change set on `video_playbacks`; none for one that is not rqlite's. */
  private def playbackEvents(changeSet: String): List[Json] = Json
    .decoder
    .decodeJson(changeSet)
    .toOption
    .flatMap(field(_, "events"))
    .flatMap(_.asArray)
    .map(_.toList)
    .getOrElse(Nil)
    .filter(event => field(event, "table").flatMap(_.asString).contains("video_playbacks"))

  /** What a change set reports for `video_playbacks`, in order: an insert of an open row starts a
    * playback; an update that sets `ended_at`, or a delete, ends one. Read from the rows
    * themselves, which rqlite sends only with `row_ids_only` off.
    */
  def playbackChanges(changeSet: String): List[PlaybackChange] =
    def id(row: Option[Json]): Option[String] = row.flatMap(field(_, "id")).flatMap(_.asString)
    def ended(row: Option[Json]): Boolean = row.flatMap(field(_, "ended_at")).exists(_ != Json.Null)
    playbackEvents(changeSet).flatMap: event =>
      val before = field(event, "before")
      val after = field(event, "after")
      field(event, "op").flatMap(_.asString) match
        case Some("INSERT") if !ended(after) =>
          id(after).map(PlaybackChange.Started(_))
        case Some("UPDATE") if ended(after) && !ended(before) =>
          id(after).map(PlaybackChange.Ended(_))
        case Some("DELETE") =>
          id(before).map(PlaybackChange.Ended(_))
        case _ =>
          None
  end playbackChanges
end VideoFileCollector
