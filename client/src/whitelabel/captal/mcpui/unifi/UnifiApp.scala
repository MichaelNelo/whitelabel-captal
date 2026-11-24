package whitelabel.captal.mcpui.unifi

import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

import org.scalajs.dom
import whitelabel.captal.mcpui.Bridge

/** The behaviour behind `ui://configure-unifi`. The markup is the server's; this fills it, posts
  * the access point and its controller, and tells the host what happened.
  *
  * Addressed bare, so everything it works on — including the ticket that authorises the POST —
  * arrives with the result of the call that opened it.
  *
  * The token goes to the server and nowhere else: never sent to the host, never read back, cleared
  * from the field once stored.
  */
object UnifiApp:

  /** What the page posts for a new controller: `UiRoutes.NewController`. */
  private val NewController = "new"

  /** Mirrors `UnifiTools.ConfigureResponse`. Field names are the contract between the two.
    */
  final class Config(raw: js.Dynamic):
    def ticket: String = raw.ticket.asInstanceOf[String]
    def slug: String = raw.slug.asInstanceOf[String]
    def name: String = raw.name.asInstanceOf[String]
    def postUrl: String = raw.postUrl.asInstanceOf[String]
    def apMac: js.UndefOr[String] = raw.apMac.asInstanceOf[js.UndefOr[String]]
    def controllerId: js.UndefOr[String] = raw.controllerId.asInstanceOf[js.UndefOr[String]]
    def redirectUrl: js.UndefOr[String] = raw.redirectUrl.asInstanceOf[js.UndefOr[String]]
    def durationMinutes: js.UndefOr[Double] = raw.durationMinutes.asInstanceOf[js.UndefOr[Double]]
    def controllers: List[Controller] = list(raw.controllers).map(Controller(_))
    def knownAccessPoints: List[Known] = list(raw.knownAccessPoints).map(Known(_))

  /** Mirrors `UnifiTools.ControllerView`. */
  final class Controller(raw: js.Dynamic):
    def id: String = raw.id.asInstanceOf[String]
    def host: String = raw.host.asInstanceOf[String]
    def port: js.UndefOr[Double] = raw.port.asInstanceOf[js.UndefOr[Double]]
    def siteId: String = raw.siteId.asInstanceOf[String]
    def describe: String = s"$host:${port.fold(443)(_.toInt)} · $siteId"

  /** Mirrors `UnifiTools.KnownAccessPointView`. */
  final class Known(raw: js.Dynamic):
    def mac: String = raw.mac.asInstanceOf[String]
    def controllerId: String = raw.controllerId.asInstanceOf[String]
    def locations: List[String] = list(raw.locations).map(_.asInstanceOf[String])

  private def list(raw: js.Dynamic): List[js.Dynamic] =
    raw.asInstanceOf[js.UndefOr[js.Array[js.Dynamic]]].fold(List.empty[js.Dynamic])(_.toList)

  private def byId(id: String) = dom.document.getElementById(id)
  private def input(id: String) = byId(id).asInstanceOf[dom.html.Input]
  private def chooser = byId("controllerId").asInstanceOf[dom.html.Select]

  /** `raw` as the server stores a MAC — lowercase, with colons — when it is one. */
  private def macOf(raw: String): Option[String] =
    val hex = raw.trim.toLowerCase.filterNot(c => c == ':' || c == '-')
    Option.when(hex.length == 12 && hex.forall(c => c.isDigit || ('a' to 'f').contains(c)))(
      hex.grouped(2).mkString(":"))

  /** The second public module out of the same linker run. See
    * [[whitelabel.captal.mcpui.UploadApp.start]] for why both apps live in one module now.
    */
  @JSExportTopLevel(name = "start", moduleID = "unifi-app")
  def start(): Unit =
    Bridge.listen: params =>
      params
        .structuredContent
        .asInstanceOf[js.UndefOr[js.Dynamic]]
        .foreach: structured =>
          structured.ticket.asInstanceOf[js.UndefOr[String]].foreach(_ => start(Config(structured)))
    // After the handshake, not before: a notification sent to a host that has not acknowledged the
    // frame has nowhere to land.
    val _ = Bridge.handshake().`then`[Unit](_ => Bridge.trackSize())

  private def start(config: Config): Unit =
    byId("form").setAttribute("class", "ready")
    byId("slug").textContent = s"${config.name} (${config.slug})"
    byId("lede").textContent =
      "Sin un AP con su controlador el portal completa el flujo pero nunca autoriza a un invitado: la sesión queda en Ready."

    val known = byId("knownAps")
    config
      .knownAccessPoints
      .foreach: point =>
        val option = dom.document.createElement("option").asInstanceOf[dom.html.Option]
        option.value = point.mac
        option.label = point.locations.mkString(", ")
        known.appendChild(option)

    config
      .controllers
      .foreach: controller =>
        chooser.appendChild(optionOf(controller.id, controller.describe))
    chooser.appendChild(optionOf(NewController, "Nuevo controlador…"))
    chooser.value = config
      .controllerId
      .getOrElse(config.controllers.headOption.fold(NewController)(_.id))

    config.apMac.foreach(input("apMac").value = _)
    config.redirectUrl.foreach(input("redirectUrl").value = _)
    config.durationMinutes.foreach(v => input("durationMinutes").value = v.toInt.toString)

    wire(config)
    follow(config)
  end start

  private def optionOf(value: String, text: String): dom.html.Option =
    val option = dom.document.createElement("option").asInstanceOf[dom.html.Option]
    option.value = value
    option.textContent = text
    option

  /** The form as the MAC and the controller chosen leave it: an access point the system has answers
    * to its own controller, which is picked and locked; a new controller asks for where it is and
    * for its token, which a controller the system has only replaces.
    */
  private def follow(config: Config): Unit =
    val note = byId("apNote")
    macOf(input("apMac").value).flatMap(mac => config.knownAccessPoints.find(_.mac == mac)) match
      case Some(point) =>
        chooser.value = point.controllerId
        chooser.disabled = true
        note.setAttribute("class", "")
        note.textContent =
          if point.locations.isEmpty then
            "El sistema ya tiene este AP: responde a su controlador."
          else
            s"El sistema ya tiene este AP, en ${point
                .locations
                .mkString(", ")}: responde a su controlador."
      case None =>
        chooser.disabled = false
        note.setAttribute("class", "hidden")
    val creating = chooser.value == NewController
    byId("newController").setAttribute(
      "class",
      if creating then
        ""
      else
        "hidden")
    List("host", "siteId", "apiToken").foreach: name =>
      if creating then
        input(name).setAttribute("required", "required")
      else
        input(name).removeAttribute("required")
  end follow

  private def wire(config: Config): Unit =
    val form = byId("form").asInstanceOf[dom.html.Form]
    val button = byId("save").asInstanceOf[dom.html.Button]
    val status = byId("status")

    input("apMac").addEventListener("input", (_: dom.Event) => follow(config))
    chooser.addEventListener("change", (_: dom.Event) => follow(config))
    form.addEventListener(
      "submit",
      (event: dom.Event) =>
        event.preventDefault()
        button.disabled = true
        status.setAttribute("class", "status")
        status.textContent = "Guardando…"
        send(config).`then`[Unit]: outcome =>
          button.disabled = false
          status.setAttribute(
            "class",
            if outcome.ok then
              "status"
            else
              "status bad")
          status.textContent = outcome.message
          if outcome.ok then
            input("apiToken").value = ""
            // A call the page makes is invisible to the model; this is what the host hears about
            // it.
            Bridge.tellTheModel(
              s"Configuré el AP ${input("apMac").value.trim} en UniFi para la location ${config
                  .slug}.")
    )
  end wire

  final private case class Outcome(ok: Boolean, message: String)

  /** The fields, named rather than scraped off the form: the request carries exactly what the
    * server reads, so a stray input added to the markup can't arrive as an unintended field.
    */
  private val Fields = List(
    "apMac",
    "host",
    "port",
    "siteId",
    "apiToken",
    "redirectUrl",
    "durationMinutes")

  /** Urlencoded rather than JSON: the iframe's origin is opaque, so this is a cross-origin POST,
    * and `application/x-www-form-urlencoded` is CORS-safelisted and skips the preflight that JSON
    * would require.
    */
  private def send(config: Config): js.Promise[Outcome] =
    val payload = new dom.URLSearchParams()
    payload.append("ticket", config.ticket)
    Fields.foreach(name => payload.append(name, input(name).value))
    // Read even while locked: a disabled field is left out of a form's own submission, not of this.
    payload.append("controllerId", chooser.value)
    val init =
      new dom.RequestInit:
        method = dom.HttpMethod.POST
        body = payload.asInstanceOf[dom.BodyInit]
    dom
      .fetch(config.postUrl, init)
      .`then`[Outcome](response =>
        response
          .text()
          .`then`[Outcome](message =>
            Outcome(response.ok, message): Outcome | js.Thenable[Outcome]): Outcome |
          js.Thenable[Outcome])
      .`catch`(_ =>
        Outcome(false, "No se pudo contactar al servidor."): Outcome | js.Thenable[Outcome])
  end send

end UnifiApp
