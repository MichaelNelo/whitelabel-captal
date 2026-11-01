package whitelabel.captal.e2e.backoffice

import java.nio.file.Path

import scala.collection.mutable
import scala.util.control.NonFatal

import com.jamesward.ziohttp.mcp.{CallToolResult, ResourceContents}
import com.microsoft.playwright.options.FunctionCallback
import com.microsoft.playwright.{FrameLocator, Locator, Page, TimeoutError}
import whitelabel.captal.e2e.scenario.{Run, World}
import whitelabel.captal.mcp.tools.{DeployTools, VideoTools}
import zio.Chunk
import zio.json.*
import zio.json.ast.Json
import zio.schema.Schema

/** The backoffice's MCP App pages, as an operator sees them: when a tool that declares a page
  * returns, the page is read from the server, rendered in a browser, in a sandboxed frame as a host
  * renders it, and told the result. What the page then asks of its host — the handshake, a
  * resource, a tool call — is answered through the same MCP client the steps use; what it tells the
  * model is kept. Nothing else of a host is here: no content security policy is applied.
  */
object Operator:
  /** The page `tool` opened, which answered `result`. */
  final class Opened(val tool: String, val page: Page, val result: CallToolResult):
    private val told = mutable.ListBuffer.empty[String]
    private val called = mutable.ListBuffer.empty[(String, CallToolResult)]

    /** The page's own document, inside the frame. */
    def app: FrameLocator = page.frameLocator("#app")

    /** What the page told the model (`ui/message`), oldest first. */
    def toldTheModel: List[String] = told.toList

    /** The tools the page called, with what each answered. */
    def toolCalls: List[(String, CallToolResult)] = called.toList

    /** Whether `done` came to hold within `seconds`. The page goes on talking to its host while
      * this waits: Playwright dispatches its calls only while a call into it is blocked.
      */
    /** Leaves the page as it is on screen for a while ([[World.linger]]), still answering it. */
    def linger(): Unit = World.linger.foreach(page.waitForTimeout(_))

    def awaitUntil(seconds: Int)(done: => Boolean): Boolean =
      try
        page.waitForCondition(
          () => done,
          Page.WaitForConditionOptions().setTimeout(seconds * 1000.0))
        true
      catch
        case _: TimeoutError =>
          false

    private[Operator] def answer(raw: String): String =
      val message = Json.decoder.decodeJson(raw).toOption.flatMap(_.asObject).getOrElse(Json.Obj())
      val id = message.get("id")
      val params = message.get("params").flatMap(_.asObject).getOrElse(Json.Obj())
      def text(name: String) = params.get(name).flatMap(_.asString)
      def reply(result: Json) =
        id.map(id => Json.Obj("jsonrpc" -> Json.Str("2.0"), "id" -> id, "result" -> result)).toList
      def refuse(why: String) =
        id.map(id =>
            Json.Obj(
              "jsonrpc" -> Json.Str("2.0"),
              "id"      -> id,
              "error"   -> Json.Obj("code" -> Json.Num(-32603), "message" -> Json.Str(why))))
          .toList
      val replies =
        try
          message.get("method").flatMap(_.asString) match
            case Some("ui/initialize") =>
              reply(
                Json.Obj(
                  "protocolVersion" -> params.get("protocolVersion").getOrElse(Json.Str("")),
                  "hostInfo"        ->
                    Json.Obj("name" -> Json.Str("captal-e2e"), "version" -> Json.Str("1")),
                  "hostCapabilities" -> Json.Obj(),
                  "hostContext"      -> Json.Obj("theme" -> Json.Str("light"))
                ))
            // The tool's result goes once the page says it is listening, as a host sends it.
            case Some("ui/notifications/initialized") =>
              List(
                Json.Obj(
                  "jsonrpc" -> Json.Str("2.0"),
                  "method"  -> Json.Str("ui/notifications/tool-result"),
                  "params"  -> encode(result)))
            case Some("resources/read") =>
              text("uri") match
                case Some(uri) =>
                  reply(
                    Json.Obj(
                      "contents" -> Json.Arr(Chunk.fromIterable(Backoffice.read(uri).map(encode)))))
                case None =>
                  refuse("resources/read names no uri")
            case Some("tools/call") =>
              text("name") match
                case Some(name) =>
                  val answered = Backoffice.call(
                    name,
                    params.get("arguments").flatMap(_.asObject).getOrElse(Json.Obj()))
                  called += name -> answered
                  reply(encode(answered))
                case None =>
                  refuse("tools/call names no tool")
            case Some("ui/message") =>
              told ++= contentTexts(params.get("content"))
              Nil
            case Some(other) =>
              refuse(s"This host does not answer $other")
            case None =>
              Nil
        catch
          case NonFatal(failure) =>
            refuse(String.valueOf(failure.getMessage))
      Json.Arr(Chunk.fromIterable(replies)).toJson
    end answer
  end Opened

  /** Calls `tool` as an operator's host does and shows the page it declares, told the result. */
  def open(tool: String, arguments: Json.Obj): Opened =
    val result = Backoffice.call(tool, arguments)
    if result.isError.contains(true) then
      throw AssertionError(s"$tool: ${result.content}")
    val uri = Backoffice.pages.getOrElse(tool, throw AssertionError(s"$tool declares no page"))
    val html = Backoffice
      .read(uri)
      .flatMap(_.text)
      .headOption
      .getOrElse(throw AssertionError(s"$uri has no page"))
    val page = World.operatorPage(tool)
    val opened = Opened(tool, page, result)
    page.exposeFunction(
      "captalHost",
      new FunctionCallback:
        def call(args: AnyRef*): AnyRef = opened.answer(String.valueOf(args.head)))
    page.setContent(HostPage)
    page.evaluate("html => { document.getElementById('app').srcdoc = html }", html)
    opened
  end open

  def open[A: Schema, B: Schema](tool: String, in: A): (B, Opened) =
    val opened = open(tool, Backoffice.args(in))
    (Backoffice.decode[B](tool, opened.result), opened)

  /** What an operator does with the page a video tool opens: chooses the spot, and the page reads
    * its length, uploads it and confirms it. Answers the tool's response and the page's
    * confirmation.
    */
  def upload[A: Schema, B: Schema](tool: String, in: A): (B, VideoTools.ConfirmUploadResponse) =
    val (response, opened) = open[A, B](tool, in)
    opened.app.locator("#row.row").waitFor(Locator.WaitForOptions().setTimeout(60_000))
    opened.linger()
    opened.app.locator("#file").setInputFiles(Path.of(Run.setting("E2E_SPOT")))
    def confirmation = opened
      .toolCalls
      .collectFirst:
        case ("confirm_video_upload", result) =>
          result
    if !opened.awaitUntil(120)(confirmation.isDefined || opened.toldTheModel.nonEmpty) then
      throw AssertionError(
        s"The upload page of $tool confirmed nothing in two minutes: ${opened
            .app
            .locator("#status")
            .textContent()}")
    val confirmed = confirmation.getOrElse(
      throw AssertionError(s"The upload page of $tool said: ${opened.toldTheModel.mkString(" ")}"))
    opened.linger()
    (
      response,
      Backoffice.decode[VideoTools.ConfirmUploadResponse]("confirm_video_upload", confirmed))
  end upload

  /** A run tool's page, watched until it says the run ended; the run then as the server has it,
    * failing with its steps unless it succeeded.
    */
  def run[A: Schema](tool: String, in: A): DeployTools.RunView =
    val (started, opened) = open[A, DeployTools.RunView](tool, in)
    if !opened.awaitUntil(10 * 60)(opened.toldTheModel.exists(_.startsWith("Terminó"))) then
      throw AssertionError(
        s"The page of $tool for ${started.slug} did not see its run end in ten minutes: ${opened
            .app
            .locator("#status")
            .textContent()}")
    opened.linger()
    Backoffice.awaitRun(started)

  private def encode(result: CallToolResult): Json = summon[JsonCodec[CallToolResult]]
    .encoder
    .toJsonAST(result)
    .fold(why => throw AssertionError(s"A tool result could not be written: $why"), identity)

  private def encode(contents: ResourceContents): Json = summon[JsonCodec[ResourceContents]]
    .encoder
    .toJsonAST(contents)
    .fold(why => throw AssertionError(s"A resource could not be written: $why"), identity)

  /** `ui/message`'s content: one text block, or several. */
  private def contentTexts(content: Option[Json]): List[String] =
    val blocks = content.toList.flatMap(json => json.asArray.map(_.toList).getOrElse(List(json)))
    blocks.flatMap(_.asObject).flatMap(_.get("text")).flatMap(_.asString)

  /** The host's own page: the frame, sandboxed as a host's is, and what passes each of the page's
    * messages to [[Opened.answer]] and posts back what it answers. A size the page reports is
    * applied, so the recording shows all of it.
    */
  private val HostPage =
    """<!doctype html>
      |<html><head><meta charset="utf-8">
      |<style>body { margin: 0; background: #f4f4f5; } iframe { border: 0; width: 100%; height: 600px; display: block; }</style>
      |</head><body>
      |<iframe id="app" sandbox="allow-scripts allow-forms"></iframe>
      |<script>
      |  const frame = document.getElementById('app');
      |  window.addEventListener('message', async (event) => {
      |    if (event.source !== frame.contentWindow) return;
      |    const message = event.data;
      |    if (message && message.method === 'ui/notifications/size-changed') {
      |      frame.style.height = Math.ceil(message.params.height) + 'px';
      |    }
      |    const replies = JSON.parse(await window.captalHost(JSON.stringify(message)));
      |    for (const reply of replies) frame.contentWindow.postMessage(reply, '*');
      |  });
      |</script>
      |</body></html>""".stripMargin
end Operator
