package whitelabel.captal.mcp

import zio.test.*

/** Two artifacts now, and each is asserted against for what only it can promise.
  *
  * The page is markup: it names its module and carries the video's identity. The protocol — the
  * envelopes, the method names, the one `ui/message` the model ever hears — lives in the bundle
  * that `client.publish` ships, so that is where it is checked. It used to all be one assertion set
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
  private def read(name: String): String =
    val stream = getClass.getResourceAsStream(s"/ui/$name")
    if stream == null then
      throw new IllegalStateException(
        s"/ui/$name is missing. `mcp.test.resources` copies it from `client.bundle`.")
    else
      try new String(stream.readAllBytes(), "UTF-8")
      finally stream.close()

  /** The local modules an entry pulls in, both forms: `from"./x"` for a binding and a bare
    * `import"./x"` for a side effect.
    *
    * Matched by shape rather than by name. While the pages were their own linker run every chunk
    * was called `chunk-<hash>`; sharing a run with the portal they are named after whatever module
    * they grew from, so a `chunk-` prefix no longer identifies them — and a regex that silently
    * matched nothing would leave every protocol assertion below reading an empty string.
    */
  private val localImport = """(?:from|import)"\./([^"]+)"""".r

  private def importsOf(text: String): List[String] =
    localImport.findAllMatchIn(text).map(_.group(1)).toList.distinct

  /** Every shipped `.js` an entry reaches, concatenated. Which file a given string lands in is
    * Rollup's business — the entry or a shared chunk — and pinning that would be pinning the
    * bundler's choices rather than the protocol.
    */
  private val bundled: String =
    val entries = List("upload.js", "unifi.js")
    val reached = entries.flatMap(name => importsOf(read(name))).distinct
    (entries ++ reached).map(read).mkString("\n")
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
          // browser fetches a module script in CORS mode — which is why `client.publish` sets
          // `Access-Control-Allow-Origin`.
          page.contains("""<script type="module" src="https://cdn.test/ui/upload.js">"""),
          // And the script is named, not carried. This is what the inlining cost 9 KB gzip per
          // `resources/read` to avoid.
          !page.contains("jsonrpc")
        )
      ,
      test("the resource hints carry crossorigin, and are absent when there is nothing to hint"):
        // `crossorigin` is the whole test. A module script is fetched in CORS mode, and a hint made
        // without the attribute warms a connection — or fills a cache entry — in the *other* mode,
        // which the real request cannot use. The browser then fetches twice: the page is correct,
        // slightly slower than with no hint at all, and nothing anywhere says so.
        //
        // The second half pins the direction this degrades in. The names come from the published
        // entry via `AssetStore.scriptPreloads`, which answers with nothing when it cannot read it —
        // on the first deploy of an environment, for one — and a page with no hints has to stay a
        // page that works.
        val hinted = UploadUi.app(
          scriptBase,
          None,
          "https://cdn.test",
          Seq("https://cdn.test/ui/chunk-abc.js"))
        assertTrue(
          hinted.contains("""<link rel="preconnect" href="https://cdn.test" crossorigin="" />"""),
          hinted.contains(
            """<link rel="modulepreload" href="https://cdn.test/ui/chunk-abc.js" crossorigin="" />"""),
          // The bare page is what every existing call site renders until the store answers.
          !page.contains("modulepreload"),
          !page.contains("preconnect")
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
      // The two pages come out of one linker run and share what they have in common instead of
      // each carrying the runtime again. Asserted as "neither is self-contained, and they overlap"
      // rather than "both import chunk X": the count and the names of the shared modules are
      // Rollup's to decide, and change with every entry added to the run.
      test("the two entries share their common modules rather than duplicating them"):
        val upload = importsOf(read("upload.js"))
        val unifi = importsOf(read("unifi.js"))
        assertTrue(
          upload.nonEmpty,
          unifi.nonEmpty,
          upload.intersect(unifi).nonEmpty,
          // The protocol lives in a module both reach, which is what makes one copy of `Bridge`
          // serve both pages.
          upload.intersect(unifi).exists(name => read(name).contains("ui/message"))
        )
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
