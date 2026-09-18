package whitelabel.captal.mcp

import zio.test.*

/** The page carries no data now — it fetches. So what is worth pinning is the protocol it speaks,
  * since every one of those strings was wrong at some point and none of them fails loudly: a bad
  * envelope is silently ignored by the host, and a missing `ui/message` leaves the agent waiting on
  * a confirmation that already happened.
  */
object UploadUiSpec extends ZIOSpecDefault:
  private val page = UploadUi.app()

  def spec =
    suite("upload page")(
      test("ships no signed URL and no video id"):
        // The previous version embedded a signed PUT URL per pending video and hid the rows it was
        // not opened for. Hiding a row is not withholding the capability in it.
        assertTrue(
          page.startsWith("<!doctype html>"),
          !page.contains("X-Amz-Signature"),
          !page.contains("uploadUrl\":"),
          page.contains("captal://videos/"))
      ,
      test("opens the bridge as a client before saying anything"):
        assertTrue(
          page.contains("ui/initialize"),
          page.contains("ui/notifications/initialized"),
          page.contains("jsonrpc"),
          // the envelope that no host reads, which is what this used to send
          !page.contains("type: 'tool'")
        )
      ,
      test("takes the video id from the opening call, since the URI cannot carry it"):
        assertTrue(
          page.contains("ui/notifications/tool-result"),
          page.contains("structuredContent"))
      ,
      test("confirms the upload and says so in the conversation"):
        // Two separate things: the tool call puts the video in rotation, the ui/message is the only
        // reason the model ever learns that it did.
        assertTrue(
          page.contains("confirm_video_upload"),
          page.contains("tools/call"),
          page.contains("ui/message"))
      ,
      test("re-reads the ticket rather than treating expiry as an error"):
        // A presigned URL is arithmetic over the key and the clock, not an allocation, so the
        // answer to expiry is to ask again — before it lapses, and after a 403.
        assertTrue(
          page.contains("403"),
          page.contains("expiresInSeconds"),
          page.contains("resources/read"))
    )
end UploadUiSpec
