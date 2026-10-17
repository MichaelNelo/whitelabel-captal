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

  /** Reports the page's height to the host, now and whenever it changes.
    *
    * An inline app is sized by what it reports, not by what it holds: the host cannot measure
    * across the frame boundary, so with nothing to go on it applies a default height and the page
    * gets an internal scrollbar. The design guidelines name that as the thing an inline card must
    * not have — on mobile a vertical drag inside the frame scrolls the conversation, not the
    * content, so whatever is below the fold cannot be reached at all.
    *
    * Observed rather than measured once: this page grows after it loads, when the row appears and
    * again when the status line fills, and both happen after any measurement taken at startup.
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

  /** Writes the host's theme onto `:root`, from the handshake's own reply.
    *
    * These do not arrive by themselves. The host returns a `hostContext` with the palette, the
    * fonts and the current theme, and an app that ignores it — as this did — renders against
    * nothing: every `var(--color-text-primary, …)` falls through to whatever literal follows it,
    * which is how a page written for both themes came out light inside a dark conversation.
    *
    * The official SDK does this with `applyHostStyleVariables` and `applyDocumentTheme`. Here it is
    * twenty lines against the same payload, because this page is Scala.js and does not carry that
    * bundle.
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
