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
    )
end UploadUiSpec
