package whitelabel.captal.mcp.tools

import io.getquill.*
import whitelabel.captal.cdc.protocol.ChangeFeedFollower
import whitelabel.captal.core.video
import whitelabel.captal.mcp.aws.AssetStore
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.core.given
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.{VideoFileRow, VideoFiles, VideoPlaybackRow}
import zio.*
import zio.stream.ZStream

/** Deletes the objects of replaced video files once nobody is playing them.
  *
  * Run when a replacement is confirmed, since the previous file may have no playback left already;
  * on every change set the change feed relays, since a playback ending can be what frees one; when
  * [[PlaybackExpiry]] closes a playback its page never ended; and once at startup, for whatever
  * changed while this server was not there to hear it.
  */
object VideoFileCollector:

  /** The objects deleted, for `videoId` alone or for every video. */
  def collect(videoId: Option[video.Id]): ZIO[QuillSqlite & AssetStore, Nothing, Int] = ZIO
    .serviceWithZIO[QuillSqlite]: quill =>
      import quill.*
      for
        files <-
          run(query[VideoFileRow].filter(f => f.deletedAt.isEmpty && f.confirmedAt.isDefined)).orDie
        open <- run(query[VideoPlaybackRow].filter(_.endedAt.isEmpty).map(_.fileId)).orDie
        watching   = open.groupMapReduce(identity)(_ => 1)(_ + _)
        candidates =
          files
            .filter(f => videoId.forall(_ == f.videoId))
            .groupBy(_.videoId)
            .values
            .flatMap: versions =>
              val current = versions.maxBy(_.confirmedAt)
              versions.filter: f =>
                video
                  .VideoFile(f.url, current = f.id == current.id, watching.getOrElse(f.id, 0))
                  .collectable
            .toList
        deleted <- ZIO.foreach(candidates)(delete(quill, _))
      yield deleted.count(identity)

  /** Marks it first, in the one statement that also checks nobody started playing it since it was
    * read; only a file that statement marked has its object deleted.
    */
  private def delete(quill: QuillSqlite, file: VideoFileRow): ZIO[AssetStore, Nothing, Boolean] =
    for
      now    <- Clock.instant
      marked <- VideoFiles.condemn(quill, file.id, now).orDie
      store  <- ZIO.service[AssetStore]
      _      <-
        ZIO.when(marked)(
          store
            .deleteIfPresent(store.keyOf(file.url))
            .tapError(why =>
              ZIO.logWarning(
                s"Video file ${file.id} is marked deleted but its object is still there: $why"))
            .ignore)
    yield marked

  /** What the change feed drives: a collection pass, and a timer per playback that started. */
  final case class Running(asked: Queue[Unit], timers: PlaybackExpiry.Timers)

  /** What a change set says happened to a playback. */
  enum PlaybackChange:
    case Started(id: String)
    case Ended(id: String)

  /** A pass whenever asked, one at a time: posts that arrive during a pass are folded into the one
    * after it, so a burst of playbacks ending costs two passes, not one each.
    */
  def runner: ZIO[QuillSqlite & AssetStore & Scope, Nothing, Running] =
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

  /** Follows the change feed for as long as it runs: see [[changed]]. */
  def follow(running: Running, feed: ZStream[Any, Nothing, ChangeFeedFollower.Event]): UIO[Unit] =
    feed.foreach(changed(running, _))

  /** Every change set asks for a pass; a playback starting starts its timer, and one ending cancels
    * it. A gap — change sets that may never arrive — resyncs the timers with the database, as at
    * startup, and asks for a pass too.
    */
  def changed(running: Running, event: ChangeFeedFollower.Event): UIO[Unit] =
    event match
      case ChangeFeedFollower.Event.Changed(_, json) =>
        ZIO
          .foreachDiscard(playbackChanges(json)):
            case PlaybackChange.Started(id) =>
              running.timers.start(id)
            case PlaybackChange.Ended(id) =>
              running.timers.cancel(id)
          .zipRight(running.asked.offer(()))
          .unit
      case ChangeFeedFollower.Event.Gap =>
        running.timers.resync *> running.asked.offer(()).unit

  /** What a change set reports for `video_playbacks`, in order: an insert of an open row starts a
    * playback; an update that sets `ended_at`, or a delete, ends one. Read from the rows
    * themselves, which rqlite sends only with `row_ids_only` off.
    */
  def playbackChanges(changeSet: String): List[PlaybackChange] =
    import zio.json.ast.Json
    def field(json: Json, name: String): Option[Json] = json.asObject.flatMap(_.get(name))
    def items(json: Option[Json]): List[Json] = json.flatMap(_.asArray).map(_.toList).getOrElse(Nil)
    def id(row: Option[Json]): Option[String] = row.flatMap(field(_, "id")).flatMap(_.asString)
    def ended(row: Option[Json]): Boolean = row.flatMap(field(_, "ended_at")).exists(_ != Json.Null)
    Json
      .decoder
      .decodeJson(changeSet)
      .toOption
      .toList
      .flatMap(set => items(field(set, "events")))
      .filter(event => field(event, "table").flatMap(_.asString).contains("video_playbacks"))
      .flatMap: event =>
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
