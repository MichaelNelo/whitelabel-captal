package whitelabel.captal.mcpui.run

import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

import org.scalajs.dom
import whitelabel.captal.mcpui.Bridge

/** The behaviour behind `ui://deploy-run`. The markup is the server's; this lists the run's steps,
  * keeps them current from the server's stream, and tells the host when the run ends.
  *
  * Addressed bare, so the run — and the ticket that lets this page read it — arrives with the
  * result of the call that opened it.
  */
object RunApp:

  /** Mirrors `DeployTools.RunView`. Field names are the contract between the two. */
  final class Run(raw: js.Dynamic):
    def runId: String = raw.runId.asInstanceOf[String]
    def slug: String = raw.slug.asInstanceOf[String]
    def operation: String = raw.operation.asInstanceOf[String]
    def status: String = raw.status.asInstanceOf[String]
    def steps: js.Array[js.Dynamic] = raw.steps.asInstanceOf[js.Array[js.Dynamic]]
    def error: Option[String] = text(raw.error)
    def ticket: Option[String] = present(raw.watch).flatMap(watch => text(watch.ticket))
    def streamUrl: Option[String] = present(raw.watch).flatMap(watch => text(watch.streamUrl))

  /** An absent `Option` arrives as `null` or not at all, depending on the encoder. */
  private def present(value: js.Dynamic): Option[js.Dynamic] = Option(value).filterNot(
    js.isUndefined)

  private def text(value: js.Dynamic): Option[String] = present(value).map(_.toString)

  private val Doing = Map(
    "deploy"  -> "Desplegando",
    "pause"   -> "Pausando",
    "resume"  -> "Reanudando",
    "destroy" -> "Dando de baja")

  private val Done = Map(
    "deploy"  -> "Desplegado",
    "pause"   -> "Pausado",
    "resume"  -> "Reanudado",
    "destroy" -> "Dado de baja")

  /** How long to wait before following the run again after the stream dropped. */
  private val RetryAfterMillis = 3000.0

  private def byId(id: String) = dom.document.getElementById(id)

  /** A third public module out of the same linker run. See
    * [[whitelabel.captal.mcpui.UploadApp.start]] for why the pages share one.
    */
  @JSExportTopLevel(name = "start", moduleID = "run-app")
  def start(): Unit =
    var following = false
    Bridge.listen: params =>
      params
        .structuredContent
        .asInstanceOf[js.UndefOr[js.Dynamic]]
        .foreach: structured =>
          if !following && !js.isUndefined(structured.runId) then
            following = true
            follow(Run(structured))
    // After the handshake, not before: a notification sent to a host that has not acknowledged the
    // frame has nowhere to land.
    val _ = Bridge.handshake().`then`[Unit](_ => Bridge.trackSize())

  private def follow(initial: Run): Unit =
    // Only a run this page watched move is worth announcing: a conversation reopened later shows a
    // run that ended long ago, and the model already knows how it ended.
    var sawRunning = initial.status == "running"
    var announced = false
    def show(run: Run): Unit =
      render(run)
      if run.status == "running" then
        sawRunning = true
      else if sawRunning && !announced then
        announced = true
        Bridge.tellTheModel(
          s"Terminó el ${run.operation} de la location ${run.slug}: ${run.status}." +
            run.error.fold("")(error => s" $error"))
    show(initial)
    (initial.ticket, initial.streamUrl) match
      case (Some(ticket), Some(url)) =>
        listen(ticket, url, show)
      case _ =>
        problem("Esta página no recibió con qué seguir el run. Consultalo con get_deploy_status.")
  end follow

  private def render(run: Run): Unit =
    byId("title").textContent =
      if run.status == "succeeded" then
        Done.getOrElse(run.operation, run.operation)
      else
        Doing.getOrElse(run.operation, run.operation)
    byId("slug").textContent = run.slug
    byId("lede").textContent =
      run.status match
        case "running" =>
          "Sigue en el servidor: no hace falta dejar esta conversación abierta."
        case "succeeded" =>
          "Terminó."
        case "interrupted" =>
          s"El servidor se detuvo antes de terminar. Volver a llamar a ${run
              .operation}_location lo completa."
        case _ =>
          s"Falló. Una vez resuelta la causa, volver a llamar a ${run
              .operation}_location retoma desde donde quedó."
    val list = byId("steps")
    list.innerHTML = ""
    run
      .steps
      .foreach: step =>
        val item = dom.document.createElement("li")
        item.setAttribute("class", step.state.toString)
        item.appendChild(dom.document.createTextNode(step.name.toString))
        text(step.detail).foreach: detail =>
          val note = dom.document.createElement("small")
          note.textContent = detail
          item.appendChild(note)
        list.appendChild(item)
    val status = byId("status")
    run.error match
      case Some(error) =>
        status.setAttribute("class", "status bad")
        status.textContent = error
      case None =>
        status.setAttribute("class", "status")
        status.textContent = ""
  end render

  private def problem(message: String): Unit =
    val status = byId("status")
    status.setAttribute("class", "status bad")
    status.textContent = message

  /** Posts the ticket and reads the run back as Server-Sent Events, each one the whole run.
    *
    * Urlencoded for the same reason as the UniFi page: a cross-origin POST with that body skips the
    * preflight. When the stream ends before the run did — a restart, a proxy, a dropped connection
    * — it follows the run again from where it is.
    */
  private def listen(ticket: String, url: String, show: Run => Unit): Unit =
    var finished = false
    def again(): Unit =
      if !finished then
        val _ = dom.window.setTimeout(() => listen(ticket, url, show), RetryAfterMillis)
    val payload = new dom.URLSearchParams()
    payload.append("ticket", ticket)
    val init =
      new dom.RequestInit:
        method = dom.HttpMethod.POST
        body = payload.asInstanceOf[dom.BodyInit]
    val received: Run => Unit =
      run =>
        if run.status != "running" then
          finished = true
        show(run)
    dom
      .fetch(url, init)
      .`then`[Unit](response =>
        if response.ok then
          read(
            response.asInstanceOf[js.Dynamic].body.getReader(),
            js.Dynamic.newInstance(js.Dynamic.global.TextDecoder)(),
            "",
            received).`then`[Unit](_ => again()): Unit | js.Thenable[Unit]
        else
          response.text().`then`[Unit](message => problem(message)): Unit | js.Thenable[Unit])
      .`catch`[Unit](_ => again(): Unit | js.Thenable[Unit])
    ()
  end listen

  /** Reads the stream to its end, handing over every complete event's data. An event is the lines
    * up to a blank one; a line starting with a colon is a comment, which the server sends nothing
    * else in.
    */
  private def read(
      reader: js.Dynamic,
      decoder: js.Dynamic,
      pending: String,
      received: Run => Unit): js.Promise[Unit] = reader
    .read()
    .asInstanceOf[js.Promise[js.Dynamic]]
    .`then`[Unit]: chunk =>
      if chunk.done.asInstanceOf[Boolean] then
        (): Unit | js.Thenable[Unit]
      else
        val text = pending + decoder.decode(chunk.value, js.Dynamic.literal(stream = true)).toString
        val events = text.split("\n\n", -1)
        events
          .init
          .foreach: event =>
            val data = event
              .split("\n")
              .filter(_.startsWith("data:"))
              .map(_.stripPrefix("data:").stripPrefix(" "))
              .mkString("\n")
            if data.nonEmpty then
              received(Run(js.JSON.parse(data)))
        read(reader, decoder, events.last, received): Unit | js.Thenable[Unit]

end RunApp
