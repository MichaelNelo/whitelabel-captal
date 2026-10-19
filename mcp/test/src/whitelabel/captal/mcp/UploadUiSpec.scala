package whitelabel.captal.mcp

import whitelabel.captal.mcp.apps.UploadUi
import zio.test.*

/** Two artifacts, each asserted for what only it can promise: the page is markup that names its
  * module and carries the video's identity, while the protocol lives in the bundle that
  * `client.publish` ships. None of those protocol strings fails loudly.
  */
object UploadUiSpec extends ZIOSpecDefault:
  /** Stands in for `AssetStore.scriptUrl`; a distinctive host so an assertion about the `<script>`
    * tag cannot pass by matching something else on the page.
    */
  private val scriptBase: String => String = name => s"https://cdn.test/ui/$name"

  private val page = UploadUi.app(scriptBase)

  /** Reads a shipped `/ui/<name>` off the test classpath; `mcp.test.resources` copies these from
    * `client.bundle`.
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
    * `import"./x"` for a side effect. Matched by shape, since chunk names are not stable.
    */
  private val localImport = """(?:from|import)"\./([^"]+)"""".r

  private def importsOf(text: String): List[String] =
    localImport.findAllMatchIn(text).map(_.group(1)).toList.distinct

  /** Every shipped `.js` an entry reaches, concatenated, so an assertion does not depend on which
    * chunk a string lands in.
    */
  private val bundled: String =
    val entries = List("upload.js", "unifi.js")
    val reached = entries.flatMap(name => importsOf(read(name))).distinct
    (entries ++ reached).map(read).mkString("\n")
  end bundled

  def spec =
    suite("upload page")(
      test("names its module and ships no signed URL"):
        assertTrue(
          page.startsWith("<!doctype html>"),
          !page.contains("X-Amz-Signature"),
          !page.contains("uploadUrl\":"),
          // `type="module"` is load-bearing twice: the entry imports its chunk, and the browser
          // fetches a module script in CORS mode, so `client.publish` must send
          // `Access-Control-Allow-Origin`.
          page.contains("""<script type="module" src="https://cdn.test/ui/upload.js">"""),
          // The script is named, not carried.
          !page.contains("jsonrpc")
        )
      ,
      test("the resource hints carry crossorigin, and are absent when there is nothing to hint"):
        // A module script is fetched in CORS mode, so a hint without `crossorigin` warms the other
        // mode. Hint names come from `AssetStore.scriptPreloads`, which can answer with nothing.
        val hinted = UploadUi.app(
          scriptBase,
          None,
          "https://cdn.test",
          Seq("https://cdn.test/ui/chunk-abc.js"))
        assertTrue(
          hinted.contains("""<link rel="preconnect" href="https://cdn.test" crossorigin="" />"""),
          hinted.contains(
            """<link rel="modulepreload" href="https://cdn.test/ui/chunk-abc.js" crossorigin="" />"""),
          // The bare page renders no hints until the store answers.
          !page.contains("modulepreload"),
          !page.contains("preconnect")
        )
      ,
      test("the bundle opens the bridge as a client before saying anything"):
        assertTrue(
          bundled.contains("ui/initialize"),
          bundled.contains("ui/notifications/initialized"),
          bundled.contains("jsonrpc"),
          // an envelope no host reads
          !bundled.contains("type: 'tool'")
        )
      ,
      test("the bundle takes the video id from the opening call, since the URI cannot carry it"):
        assertTrue(
          bundled.contains("ui/notifications/tool-result"),
          bundled.contains("structuredContent"),
          // The resource it then reads, built by `ticketUri` in the module.
          bundled.contains("captal://videos/")
        )
      ,
      test("the bundle confirms the upload and says so in the conversation"):
        // The tool call puts the video in rotation; the `ui/message` is how the model learns it did.
        assertTrue(
          bundled.contains("confirm_video_upload"),
          bundled.contains("tools/call"),
          bundled.contains("ui/message"))
      ,
      test("the bundle re-reads the ticket rather than treating expiry as an error"):
        // Expiry is answered by asking again: before it lapses, and after a 403.
        assertTrue(
          bundled.contains("403"),
          bundled.contains("expiresInSeconds"),
          bundled.contains("resources/read"))
      ,
      // The two pages come out of one linker run. Asserted as "neither is self-contained, and they
      // overlap" rather than "both import chunk X", since chunk names are not stable.
      test("the two entries share their common modules rather than duplicating them"):
        val upload = importsOf(read("upload.js"))
        val unifi = importsOf(read("unifi.js"))
        assertTrue(
          upload.nonEmpty,
          unifi.nonEmpty,
          upload.intersect(unifi).nonEmpty,
          // The protocol lives in a module both reach, so one copy of `Bridge` serves both pages.
          upload.intersect(unifi).exists(name => read(name).contains("ui/message"))
        )
      ,
      test("addressed per video, the id is in the shell and the host is not needed for it"):
        // `_meta.ui.resourceUri` can only name the bare page; `resources/list` enumerates one page
        // per pending video, and that form carries its id.
        val bare = UploadUi.app(scriptBase)
        val forOne = UploadUi.app(
          scriptBase,
          Some(UploadUi.PendingVideo("v-42", "Promo de invierno", "bar-norte")))
        assertTrue(
          forOne.contains("""data-video="v-42""""),
          // The server knows these before the page opens, so they are markup, not a fetch.
          forOne.contains("Promo de invierno"),
          forOne.contains("bar-norte"),
          // Any video: what arrives is checked at confirm rather than declared here.
          forOne.contains("""accept="video/*""""),
          // The script reads the attribute either way, so this looks for the attribute itself.
          !bare.contains("""data-video="""" + "\""),
          // one video, always: the page has no listing mode
          !forOne.contains("captal://videos/pending")
        )
    )
end UploadUiSpec
