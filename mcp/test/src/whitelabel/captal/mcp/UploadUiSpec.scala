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
      ,
      test("addressed per video, the id is in the shell and the host is not needed for it"):
        // `_meta.ui.resourceUri` can only ever name the bare page — it is built before any call has
        // produced a video. But `resources/list` enumerates one page per pending video, and that
        // form carries its id, so opening it from the resource picker needs no tool result.
        val bare = UploadUi.app()
        val forOne = UploadUi.app(
          Some(UploadUi.PendingVideo("v-42", "Promo de invierno", "bar-norte")))
        assertTrue(
          forOne.contains("""data-video="v-42""""),
          // The server knows these before the page opens, so they are markup, not a fetch.
          forOne.contains("Promo de invierno"),
          forOne.contains("bar-norte"),
          // Any video: the type is not known before the operator picks a file, and what
          // arrives is checked at confirm rather than declared here.
          forOne.contains("""accept="video/*""""),
          // The script reads the attribute either way, so this looks for the attribute itself.
          !bare.contains("""data-video="""" + "\""),
          // one video, always: no listing mode left in the page
          !forOne.contains("captal://videos/pending")
        )
    )
end UploadUiSpec
