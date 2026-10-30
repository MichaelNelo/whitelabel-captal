package whitelabel.captal.mcp

import whitelabel.captal.mcp.tools.{PlaybackExpiry, VideoFileCollector, VideoTools}
import zio.test.*

/** Where the video's length comes from — read from the file by the upload page and recorded at
  * confirm — and how `minWatchSeconds` resolves against it.
  */
object VideoToolsSpec extends ZIOSpecDefault:

  def spec =
    suite("VideoTools")(
      suite("minWatchSeconds, resolved once the file's length is known")(
        // Zero is what create stores when the caller omits it.
        test("omitted means the whole video"):
          assertTrue(VideoTools.resolveMinWatch(0, 30) == 30)
        ,
        test("a shorter request is kept"):
          assertTrue(VideoTools.resolveMinWatch(10, 30) == 10)
        ,
        // Lowered, not refused: by the time this runs the file is in S3 and no tool edits the
        // field.
        test("a request longer than the video is lowered to its length"):
          assertTrue(VideoTools.resolveMinWatch(45, 30) == 30)
        ,
        test("equal to the length is left alone"):
          assertTrue(VideoTools.resolveMinWatch(30, 30) == 30)
        ,
        // The property the three cases above are instances of.
        test("the result never exceeds the video"):
          val cases =
            for
              requested <- 0 to 60
              duration  <- 1 to 60
            yield VideoTools.resolveMinWatch(requested, duration) <= duration
          assertTrue(cases.forall(identity))
      ),
      test("create_advertiser_video no longer asks for a duration"):
        val rendered = (VideoTools.create.definition.inputSchema: zio.json.ast.Json).toString
        assertTrue(!rendered.contains("durationSeconds"), rendered.contains("minWatchSeconds"))
      ,
      test("confirm_video_upload asks for it instead"):
        val rendered = (VideoTools.confirm.definition.inputSchema: zio.json.ast.Json).toString
        assertTrue(rendered.contains("durationSeconds"), rendered.contains("videoId"))
      ,
      // A promo is the venue's own, so it has no advertiser to attribute it to; it may still carry
      // a survey.
      test("a promo asks for no advertiser, and for questions only optionally"):
        val promo = (VideoTools.createPromo.definition.inputSchema: zio.json.ast.Json).toString
        val ad = (VideoTools.create.definition.inputSchema: zio.json.ast.Json).toString
        assertTrue(
          !promo.contains("advertiserId"),
          promo.contains("questions"),
          ad.contains("advertiserId"),
          ad.contains("questions"),
          // `noRepeatSeconds` only means something for a rotation.
          promo.contains("noRepeatSeconds"),
          !ad.contains("noRepeatSeconds")
        )
      ,
      // The asymmetry read off `required` rather than off the prose.
      test("questions are required of an advertiser video and not of a promo"):
        def requiredOf(tool: com.jamesward.ziohttp.mcp.McpToolHandlerR[?]) =
          val schema: zio.json.ast.Json = tool.definition.inputSchema
          schema
            .get(zio.json.ast.JsonCursor.field("required"))
            .toOption
            .map(_.toString)
            .getOrElse("")
        assertTrue(
          requiredOf(VideoTools.create).contains("questions"),
          !requiredOf(VideoTools.createPromo).contains("questions"))
      ,
      // Both halves of the upload are shared: a promo is resumed and confirmed by the same two
      // tools.
      test("a promo uses the same upload page as an advertiser video"):
        def uiOf(tool: com.jamesward.ziohttp.mcp.McpToolHandlerR[?]) = tool
          .definition
          .meta
          .map(m => (m: zio.json.ast.Json).toString)
          .getOrElse("")
        assertTrue(
          uiOf(VideoTools.createPromo).contains("ui://upload-video"),
          uiOf(VideoTools.createPromo) == uiOf(VideoTools.create))
      ,
      // What rqlite's change data capture reaches: a post asks for a pass, whatever it carries,
      // and a burst asks for one; a playback starting starts its timer, and one ending cancels it.
      test("a change posted by rqlite asks for one pass, and starts and cancels timers"):
        for
          asked <- zio.Queue.sliding[Unit](1)
          calls <- zio.Ref.make(List.empty[String])
          timers = PlaybackExpiry.Timers(
            start = id => calls.update(_ :+ s"start $id"),
            cancel = id => calls.update(_ :+ s"cancel $id"),
            pending = zio.ZIO.succeed(Set.empty))
          running = VideoFileCollector.Running(asked, timers)
          post    =
            (body: String) =>
              VideoFileCollector
                .routes(running)
                .runZIO(
                  zio.http.Request.post(VideoFileCollector.CdcPath, zio.http.Body.fromString(body)))
          first  <- post(InsertThenEnd)
          _      <- post("not what rqlite sends")
          queued <- asked.size
          made   <- calls.get
        yield assertTrue(
          first.status == zio.http.Status.Ok,
          queued == 1,
          made == List("start p-1", "cancel p-1"))
      ,
      test("a playback starts on an open insert and ends on an update that ends it or a delete"):
        import VideoFileCollector.PlaybackChange.{Ended, Started}
        assertTrue(
          VideoFileCollector.playbackChanges(InsertThenEnd) == List(Started("p-1"), Ended("p-1")),
          VideoFileCollector.playbackChanges(InsertThenDelete) ==
            List(Started("p-2"), Ended("p-2")),
          VideoFileCollector.playbackChanges("""{"node_id":"n1","payload":[]}""") == Nil,
          VideoFileCollector.playbackChanges("{") == Nil
        )
    )

  /** What rqlite 10.6.1 posted for an insert and then an update ending the same row, with
    * `row_ids_only` off, captured from a running node.
    */
  private val InsertThenEnd =
    """{"node_id":"90b33141e85d","payload":[{"index":3,"events":[{"op":"INSERT","table":"video_playbacks","new_row_id":1,"after":{"ended_at":null,"file_id":"f-1","id":"p-1","session_id":"s-1","started_at":"2026-10-30T10:00:00.000000000Z"}},{"op":"UPDATE","table":"video_playbacks","new_row_id":1,"old_row_id":1,"before":{"ended_at":null,"file_id":"f-1","id":"p-1","session_id":"s-1","started_at":"2026-10-30T10:00:00.000000000Z"},"after":{"ended_at":"2026-10-30T10:01:00.000000000Z","file_id":"f-1","id":"p-1","session_id":"s-1","started_at":"2026-10-30T10:00:00.000000000Z"}}]}]}"""

  /** The same for an insert and then a delete: a delete carries the row only in `before`. */
  private val InsertThenDelete =
    """{"node_id":"66013d2433fb","payload":[{"index":3,"events":[{"op":"INSERT","table":"video_playbacks","new_row_id":1,"after":{"ended_at":null,"file_id":"f-1","id":"p-2","session_id":"s-1","started_at":"2026-10-30T10:00:00.000000000Z"}},{"op":"DELETE","table":"video_playbacks","old_row_id":1,"before":{"ended_at":null,"file_id":"f-1","id":"p-2","session_id":"s-1","started_at":"2026-10-30T10:00:00.000000000Z"}}]}]}"""
end VideoToolsSpec
