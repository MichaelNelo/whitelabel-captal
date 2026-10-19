package whitelabel.captal.mcp

import whitelabel.captal.mcp.tools.VideoTools
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
    )
end VideoToolsSpec
