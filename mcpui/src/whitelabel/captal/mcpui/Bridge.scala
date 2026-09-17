package whitelabel.captal.mcpui

import scala.scalajs.js

import org.scalajs.dom

/** JSON-RPC 2.0 over `postMessage`, on `js.Promise` rather than `scala.concurrent.Future`.
  *
  * The page is inlined into the HTML the host renders, so its size is paid on every open. Scala's
  * Future, its ExecutionContext and the collections around them are the bulk of what a Scala.js
  * bundle carries for work like this; the host already has promises.
  */
object Bridge:
  private var nextId = 1
  private val waiting = js.Dictionary.empty[js.Function1[js.Dynamic, Unit]]

  def listen(onToolResult: js.Dynamic => Unit): Unit = dom
    .window
    .addEventListener(
      "message",
      (event: dom.MessageEvent) =>
        val message = event.data.asInstanceOf[js.Dynamic]
        if message != null && !js.isUndefined(message) &&
          message.jsonrpc.asInstanceOf[js.UndefOr[String]].contains("2.0") then
          val id = message.id.asInstanceOf[js.UndefOr[Int]]
          id.toOption.map(_.toString).flatMap(waiting.remove) match
            case Some(resolve) =>
              resolve(message.result.asInstanceOf[js.Dynamic])
            case None =>
              if message
                  .method
                  .asInstanceOf[js.UndefOr[String]]
                  .contains("ui/notifications/tool-result") then
                onToolResult(message.params.asInstanceOf[js.Dynamic])
    )

  private def post(payload: js.Dynamic): Unit = dom.window.parent.postMessage(payload, "*")

  def call(method: String, params: js.Dynamic): js.Promise[js.Dynamic] =
    val id = nextId
    nextId += 1
    new js.Promise[js.Dynamic]((resolve, _) =>
      waiting(id.toString) = (value: js.Dynamic) => resolve(value)
      post(js.Dynamic.literal(jsonrpc = "2.0", id = id, method = method, params = params)))

  def notify(method: String, params: js.Dynamic): Unit = post(
    js.Dynamic.literal(jsonrpc = "2.0", method = method, params = params))

  def readJson(uri: String): js.Promise[js.Dynamic] = call(
    "resources/read",
    js.Dynamic.literal(uri = uri)).`then`[js.Dynamic]: result =>
    val text = result.contents.asInstanceOf[js.Array[js.Dynamic]](0).text.asInstanceOf[String]
    js.JSON.parse(text)

  def handshake(): js.Promise[Unit] = call(
    "ui/initialize",
    js.Dynamic
      .literal(appCapabilities = js
        .Dynamic
        .literal(availableDisplayModes = js.Array("inline", "fullscreen")))).`then`[Unit](_ =>
    notify("ui/notifications/initialized", js.Dynamic.literal()))

  def callTool(name: String, arguments: js.Dynamic): js.Promise[js.Dynamic] = call(
    "tools/call",
    js.Dynamic.literal(name = name, arguments = arguments))

  def tellTheModel(text: String): Unit = notify(
    "ui/message",
    js.Dynamic.literal(role = "user", content = js.Dynamic.literal(`type` = "text", text = text)))
end Bridge
