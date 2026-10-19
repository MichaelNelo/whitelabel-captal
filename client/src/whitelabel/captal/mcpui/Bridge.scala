package whitelabel.captal.mcpui

import scala.scalajs.js

import org.scalajs.dom

/** JSON-RPC 2.0 over `postMessage`, on `js.Promise` rather than `scala.concurrent.Future`.
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

  /** Reports the page's height to the host, now and whenever it changes.
    *
    * The host cannot measure across the frame boundary; without a report it applies a default
    * height and the page gets an internal scrollbar.
    */
  def trackSize(): Unit =
    def report(): Unit =
      val root = dom.document.documentElement
      notify(
        "ui/notifications/size-changed",
        js.Dynamic.literal(width = root.scrollWidth.toDouble, height = root.scrollHeight.toDouble))
    report()
    new dom.ResizeObserver((_, _) => report()).observe(dom.document.body)

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
        .literal(availableDisplayModes = js.Array("inline", "fullscreen")))).`then`[Unit]: result =>
    applyHostStyles(result)
    notify("ui/notifications/initialized", js.Dynamic.literal())

  /** Writes the host's theme and style variables onto `:root`, from the handshake reply's
    * `hostContext`.
    */
  private def applyHostStyles(result: js.Dynamic): Unit =
    val context = result.hostContext.asInstanceOf[js.UndefOr[js.Dynamic]]
    context.foreach: ctx =>
      val root = dom.document.documentElement.asInstanceOf[dom.html.Element]
      ctx
        .theme
        .asInstanceOf[js.UndefOr[String]]
        .foreach: theme =>
          root.setAttribute("data-theme", theme)
          // Claude's tokens are `light-dark()` values, so they resolve to the wrong half until the
          // root's own colour scheme says which half we are in.
          root.style.setProperty("color-scheme", theme)
      ctx
        .styles
        .asInstanceOf[js.UndefOr[js.Dynamic]]
        .foreach: styles =>
          styles
            .variables
            .asInstanceOf[js.UndefOr[js.Dictionary[String]]]
            .foreach: variables =>
              variables.foreach((name, value) => root.style.setProperty(name, value))
          styles
            .css
            .asInstanceOf[js.UndefOr[js.Dynamic]]
            .foreach: css =>
              css
                .fonts
                .asInstanceOf[js.UndefOr[String]]
                .foreach: fonts =>
                  val tag = dom.document.createElement("style")
                  tag.textContent = fonts
                  val _ = dom.document.head.appendChild(tag)
  end applyHostStyles

  def callTool(name: String, arguments: js.Dynamic): js.Promise[js.Dynamic] = call(
    "tools/call",
    js.Dynamic.literal(name = name, arguments = arguments))

  def tellTheModel(text: String): Unit = notify(
    "ui/message",
    js.Dynamic.literal(role = "user", content = js.Dynamic.literal(`type` = "text", text = text)))
end Bridge
