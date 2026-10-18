package whitelabel.captal.mcp

import whitelabel.captal.mcp.tools.VideoTools
import zio.test.*

/** Where the video's length comes from, and what it settles.
  *
  * It used to be an argument to `create_advertiser_video`: a number supplied before any file
  * existed, by whoever was describing the video rather than by the video. Nothing could check it,
  * and a wrong one is invisible in the backoffice — it reaches guests as a countdown that ends in
  * the wrong place. It is now read from the file by the upload page and recorded at confirm.
  *
  * That move leaves `minWatchSeconds` to be resolved late, which is what these pin.
  */
object VideoToolsSpec extends ZIOSpecDefault:

  def spec =
    suite("VideoTools")(
      suite("minWatchSeconds, resolved once the file's length is known")(
        // Zero is what create stores when the caller omits it, because at that point there is no
        // length to default to.
        test("omitted means the whole video"):
          assertTrue(VideoTools.resolveMinWatch(0, 30) == 30)
        ,
        test("a shorter request is kept"):
          assertTrue(VideoTools.resolveMinWatch(10, 30) == 10)
        ,
        // Lowered, not refused. By the time this runs the file is in S3 and there is no tool to
        // edit the field, so refusing would leave an upload that cannot be completed — and a
        // survey that unlocks after the video ends never unlocks at all.
        test("a request longer than the video is lowered to its length"):
          assertTrue(VideoTools.resolveMinWatch(45, 30) == 30)
        ,
        test("equal to the length is left alone"):
          assertTrue(VideoTools.resolveMinWatch(30, 30) == 30)
        ,
        // The property the three cases above are instances of: the survey always unlocks at or
        // before the end, whatever was asked for.
        test("the result never exceeds the video"):
          val cases =
            for
              requested <- 0 to 60
              duration  <- 1 to 60
            yield VideoTools.resolveMinWatch(requested, duration) <= duration
          assertTrue(cases.forall(identity))
      ),
      test("create_advertiser_video no longer asks for a duration"):
        // The schema is the contract an agent reads before asking the operator anything. While it
        // named a duration, the agent asked for one — which is exactly the number nobody has.
        val rendered = (VideoTools.create.definition.inputSchema: zio.json.ast.Json).toString
        assertTrue(!rendered.contains("durationSeconds"), rendered.contains("minWatchSeconds"))
      ,
      test("confirm_video_upload asks for it instead"):
        val rendered = (VideoTools.confirm.definition.inputSchema: zio.json.ast.Json).toString
        assertTrue(rendered.contains("durationSeconds"), rendered.contains("videoId"))
      ,
      // A promo is the venue's own, so it has no advertiser to attribute it to. It may still carry
      // a survey: what differs is the *selection*. `nextAdQueryAuthenticated` requires through an
      // EXISTS that a question remain unanswered, so an advertiser video with nothing left to ask
      // is never served; the promo query has no such clause and serves it regardless. Hence
      // questions mandatory on one tool and optional on the other.
      //
      // This asserted `!promo.contains("questions")`, which is part of how the belief that a promo
      // cannot have a survey held up: the read path filtered them out, the tool offered no way to
      // create one, and a test agreed with both.
      test("a promo asks for no advertiser, and for questions only optionally"):
        val promo = (VideoTools.createPromo.definition.inputSchema: zio.json.ast.Json).toString
        val ad = (VideoTools.create.definition.inputSchema: zio.json.ast.Json).toString
        assertTrue(
          !promo.contains("advertiserId"),
          promo.contains("questions"),
          ad.contains("advertiserId"),
          ad.contains("questions"),
          // And gains the one field that only means something for a rotation.
          promo.contains("noRepeatSeconds"),
          !ad.contains("noRepeatSeconds")
        )
      ,
      // The asymmetry read off `required` rather than off the prose, so a promo's questions
      // becoming mandatory — or an advertiser video's becoming optional — fails here.
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
      // Both halves of the upload are shared, so a promo is resumed and confirmed by the same two
      // tools. Pinning the page because that is what makes them interchangeable.
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
