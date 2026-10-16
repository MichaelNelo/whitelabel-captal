package whitelabel.captal.mcp

import zio.test.*

/** Two artifacts now, and each is asserted against for what only it can promise.
  *
  * The page is markup: it names its module and carries the video's identity. The protocol — the
  * envelopes, the method names, the one `ui/message` the model ever hears — lives in the bundle
  * that `mcpui.publish` ships, so that is where it is checked. It used to all be one assertion set
  * against the page, because the page inlined the script.
  *
  * Those protocol strings are still worth pinning for the reason they always were: none of them
  * fails loudly. A bad envelope is ignored by the host in silence, and a missing `ui/message`
  * leaves the agent waiting on a confirmation that already happened.
  */
object UploadUiSpec extends ZIOSpecDefault:
  /** Stands in for `AssetStore.scriptUrl`, which is what the server hands over. A distinctive host
    * so an assertion about the `<script>` tag cannot pass by matching something else on the page.
    */
  private val scriptBase: String => String = name => s"https://cdn.test/ui/$name"

  private val page = UploadUi.app(scriptBase)

  /** Every shipped `.js`, concatenated. Which file a given string lands in is Rollup's business —
    * the entry or the shared chunk — and pinning that would be pinning the bundler's choices rather
    * than the protocol.
    */
  private val bundled: String =
    val names = List("upload.js", "unifi.js")
    val entries = names.map: name =>
      val stream = getClass.getResourceAsStream(s"/ui/$name")
      if stream == null then
        throw new IllegalStateException(
          s"/ui/$name is missing. `mcp.test.resources` copies it from `mcpui.bundle`.")
      else
        try new String(stream.readAllBytes(), "UTF-8")
        finally stream.close()
    val chunks =
      // The chunk's name carries a content hash, so it cannot be listed — it is found by what the
      // entries import.
      entries
        .flatMap("""from"\./(chunk-[A-Za-z0-9_-]+\.js)"""".r.findAllMatchIn)
        .map(_.group(1))
        .distinct
        .map: name =>
          val stream = getClass.getResourceAsStream(s"/ui/$name")
          try new String(stream.readAllBytes(), "UTF-8")
          finally stream.close()
    (entries ++ chunks).mkString("\n")
  end bundled

  def spec =
    suite("upload page")(
      test("names its module and ships no signed URL"):
        // The page embedded a signed PUT URL per pending video once, and hid the rows it was not
        // opened for. Hiding a row is not withholding the capability in it.
        assertTrue(
          page.startsWith("<!doctype html>"),
          !page.contains("X-Amz-Signature"),
          !page.contains("uploadUrl\":"),
          // `type="module"` is load-bearing twice over: the entry imports its chunk, and the
          // browser fetches a module script in CORS mode — which is why `mcpui.publish` sets
          // `Access-Control-Allow-Origin`.
          page.contains("""<script type="module" src="https://cdn.test/ui/upload.js">"""),
          // And the script is named, not carried. This is what the inlining cost 9 KB gzip per
          // `resources/read` to avoid.
          !page.contains("jsonrpc")
        )
      ,
      test("the bundle opens the bridge as a client before saying anything"):
        assertTrue(
          bundled.contains("ui/initialize"),
          bundled.contains("ui/notifications/initialized"),
          bundled.contains("jsonrpc"),
          // the envelope that no host reads, which is what this used to send
          !bundled.contains("type: 'tool'")
        )
      ,
      test("the bundle takes the video id from the opening call, since the URI cannot carry it"):
        assertTrue(
          bundled.contains("ui/notifications/tool-result"),
          bundled.contains("structuredContent"),
          // The resource it then reads. Built by `ticketUri` in the module, so it is bundle-side —
          // it was only ever visible on the page because the page inlined the script.
          bundled.contains("captal://videos/")
        )
      ,
      test("the bundle confirms the upload and says so in the conversation"):
        // Two separate things: the tool call puts the video in rotation, the ui/message is the only
        // reason the model ever learns that it did.
        assertTrue(
          bundled.contains("confirm_video_upload"),
          bundled.contains("tools/call"),
          bundled.contains("ui/message"))
      ,
      test("the bundle re-reads the ticket rather than treating expiry as an error"):
        // A presigned URL is arithmetic over the key and the clock, not an allocation, so the
        // answer to expiry is to ask again — before it lapses, and after a 403.
        assertTrue(
          bundled.contains("403"),
          bundled.contains("expiresInSeconds"),
          bundled.contains("resources/read"))
      ,
      // The two pages share one chunk out of one linker run, which is the whole reason they were
      // merged into one module. Two self-contained files would each carry the runtime again.
      test("both entries import the same shared chunk"):
        val imports = """from"\./(chunk-[A-Za-z0-9_-]+\.js)"""".r
        def chunkOf(name: String) =
          val stream = getClass.getResourceAsStream(s"/ui/$name")
          val text =
            try new String(stream.readAllBytes(), "UTF-8")
            finally stream.close()
          imports.findFirstMatchIn(text).map(_.group(1))
        assertTrue(chunkOf("upload.js").isDefined, chunkOf("upload.js") == chunkOf("unifi.js"))
      ,
      test("addressed per video, the id is in the shell and the host is not needed for it"):
        // `_meta.ui.resourceUri` can only ever name the bare page — it is built before any call has
        // produced a video. But `resources/list` enumerates one page per pending video, and that
        // form carries its id, so opening it from the resource picker needs no tool result.
        val bare = UploadUi.app(scriptBase)
        val forOne = UploadUi.app(
          scriptBase,
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
