package whitelabel.captal.mcp

import zio.test.*

/** The page is rendered server-side from values the caller does not control, but the file name
  * reaches it through the content type and the ids are echoed into a script literal, so what is
  * worth pinning is that nothing escapes into the markup.
  */
object UploadUiSpec extends ZIOSpecDefault:
  private def render(videoId: String = "v-1", css: Option[String] = None) = UploadUi.render(
    videoId = videoId,
    uploadUrl = "https://bucket.s3.amazonaws.com/loc/videos/v-1.mp4?X-Amz-Signature=abc",
    contentType = "video/mp4",
    locationCss = css)

  def spec =
    suite("upload page")(
      test("carries the button, the file input and the signed URL"):
        val page = render()
        assertTrue(
          page.startsWith("<!doctype html>"),
          page.contains("Subir video"),
          page.contains("""type="file""""),
          page.contains("X-Amz-Signature=abc"),
          page.contains("confirm_video_upload")
        )
      ,
      test("inlines the location stylesheet after its own"):
        // Cascade order is what makes a themed location theme this page too.
        val page = render(css = Some(".upload { background: rebeccapurple; }"))
        assertTrue(
          page.contains("rebeccapurple"),
          page.indexOf(".upload { max-width") < page.indexOf("rebeccapurple"))
      ,
      test("renders without a stylesheet"):
        val page = render()
        assertTrue(!page.contains("rebeccapurple"), page.contains("var(--color-primary"))
      ,
      test("a video id cannot break out of the script literal"):
        // Server-derived today, but the page would be the one place an id reached markup unescaped.
        val page = render(videoId = """v";</script><script>alert(1)//""")
        assertTrue(
          !page.contains("</script><script>alert(1)"),
          page.contains("\\u003c") || page.contains("\\\""))
      ,
      test("the pending page lists one row per video, each with its own signed URL"):
        // The static page exists because `_meta.ui.resourceUri` is declared before any call has
        // produced a video, and `CallToolResult` carries no `_meta` to correct it afterwards. So
        // the URI names what the page is, not which video, and the page lists what is waiting.
        val page = UploadUi.renderPending(
          List(
            UploadUi.Pending(
              "v-1",
              "Promo verano",
              "cafe-centro",
              "https://s3/a?sig=one",
              "video/mp4"),
            UploadUi.Pending("v-2", "Spot corto", "bar-norte", "https://s3/b?sig=two", "video/webm")
          ),
          None
        )
        assertTrue(
          page.contains("Promo verano"),
          page.contains("Spot corto"),
          page.contains("cafe-centro"),
          page.contains("sig=one"),
          page.contains("sig=two"),
          // Each row confirms its own upload, or the wrong video would enter rotation.
          page.contains("confirm_video_upload"),
          page.split("""data-video="v-1"""").length - 1 >= 3
        )
      ,
      test("an empty list says so rather than rendering a page with nothing on it"):
        val page = UploadUi.renderPending(Nil, None)
        assertTrue(
          page.contains("No hay videos esperando archivo"),
          page.contains("create_advertiser_video"),
          !page.contains("""class="row""""))
    )
end UploadUiSpec
