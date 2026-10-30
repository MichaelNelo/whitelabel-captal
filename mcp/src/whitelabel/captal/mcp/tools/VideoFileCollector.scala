package whitelabel.captal.mcp.tools

import io.getquill.*
import whitelabel.captal.core.video
import whitelabel.captal.mcp.aws.AssetStore
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.core.given
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.{VideoFileRow, VideoFiles, VideoPlaybackRow}
import zio.*
import zio.http.*

/** Deletes the objects of replaced video files once nobody is playing them.
  *
  * Run when a replacement is confirmed, since the previous file may have no playback left already;
  * on every change rqlite reports to `video_playbacks`, since a playback ending can be what frees
  * one; and once at startup, for whatever changed while this server was not there to hear it.
  */
object VideoFileCollector:

  /** Where rqlite's change data capture posts. Not authenticated, and it does not need to be: the
    * body is never read. A post only asks for a pass, and a pass deletes nothing the database does
    * not itself say may go.
    */
  val CdcPath: String = "/cdc/video-playbacks"

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

  /** A pass whenever asked, one at a time: posts that arrive during a pass are folded into the one
    * after it, so a burst of playbacks ending costs two passes, not one each.
    */
  def runner: ZIO[QuillSqlite & AssetStore & Scope, Nothing, Queue[Unit]] =
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
      _ <- asked.offer(())
    yield asked

  def routes(asked: Queue[Unit]): Routes[Any, Nothing] = Routes(
    Method.POST / "cdc" / "video-playbacks" -> handler(asked.offer(()).as(Response.ok)))
end VideoFileCollector
