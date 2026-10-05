package whitelabel.captal.mcpui.unifi

import scala.scalajs.js

import org.scalajs.dom
import whitelabel.captal.mcpui.Bridge

/** The behaviour behind `ui://configure-unifi`. The markup is the server's; this fills it, posts
  * the credentials, and tells the host what happened.
  *
  * The page is addressed bare — `_meta.ui.resourceUri` is fixed when the tool is defined, long
  * before any slug is known — so everything it works on arrives with the result of the call that
  * opened it. That includes the ticket, which is what authorises the POST: this page is outside the
  * MCP protocol the moment it talks to the server directly, and the ticket is what replaces the
  * protocol's authentication for that one write.
  *
  * The token goes to the server and nowhere else. It is never put in a message to the host, never
  * read back, and cleared from the field once stored — the entire reason this page exists is that a
  * tool argument would have been recorded in the transcript.
  */
object UnifiApp:

  /** Mirrors `UnifiTools.ConfigureResponse`. Field names are the contract between the two, and the
    * reason this is a type: the server renames one and this stops compiling, rather than rendering
    * a blank form the operator fills in twice.
    */
  final class Config(raw: js.Dynamic):
    def ticket: String = raw.ticket.asInstanceOf[String]
    def slug: String = raw.slug.asInstanceOf[String]
    def name: String = raw.name.asInstanceOf[String]
    def postUrl: String = raw.postUrl.asInstanceOf[String]
    def tokenSet: Boolean = raw.tokenSet.asInstanceOf[Boolean]
    def host: js.UndefOr[String] = raw.host.asInstanceOf[js.UndefOr[String]]
    def siteId: js.UndefOr[String] = raw.siteId.asInstanceOf[js.UndefOr[String]]
    def apMac: js.UndefOr[String] = raw.apMac.asInstanceOf[js.UndefOr[String]]
    def redirectUrl: js.UndefOr[String] = raw.redirectUrl.asInstanceOf[js.UndefOr[String]]
    def port: js.UndefOr[Double] = raw.port.asInstanceOf[js.UndefOr[Double]]
    def durationMinutes: js.UndefOr[Double] = raw.durationMinutes.asInstanceOf[js.UndefOr[Double]]

  private def byId(id: String) = dom.document.getElementById(id)
  private def input(id: String) = byId(id).asInstanceOf[dom.html.Input]

  def main(args: Array[String]): Unit =
    Bridge.listen: params =>
      params
        .structuredContent
        .asInstanceOf[js.UndefOr[js.Dynamic]]
        .foreach: structured =>
          structured.ticket.asInstanceOf[js.UndefOr[String]].foreach(_ => start(Config(structured)))
    // After the handshake, not before: a notification sent to a host that has not acknowledged the
    // frame has nowhere to land. This form is the taller of the two pages, so an unreported height
    // clips it worst.
    val _ = Bridge.handshake().`then`[Unit](_ => Bridge.trackSize())

  private def start(config: Config): Unit =
    byId("form").setAttribute("class", "ready")
    byId("slug").textContent = s"${config.name} (${config.slug})"
    byId("lede").textContent =
      if config.tokenSet then
        "Esta location ya tiene un token cargado. Escribir uno nuevo lo reemplaza; dejarlo vacío conserva el actual."
      else
        "Sin estas credenciales el portal completa el flujo pero nunca autoriza a un invitado: la sesión queda en Ready."

    config.host.foreach(input("host").value = _)
    config.siteId.foreach(input("siteId").value = _)
    config.apMac.foreach(input("apMac").value = _)
    config.redirectUrl.foreach(input("redirectUrl").value = _)
    config.port.foreach(v => input("port").value = v.toInt.toString)
    config.durationMinutes.foreach(v => input("durationMinutes").value = v.toInt.toString)
    // Required only when there is nothing to fall back on: a location that already has a token
    // keeps it when the field is left blank, which is how the form never has to render it back.
    if !config.tokenSet then
      input("apiToken").setAttribute("required", "required")

    wire(config)
  end start

  private def wire(config: Config): Unit =
    val form = byId("form").asInstanceOf[dom.html.Form]
    val button = byId("save").asInstanceOf[dom.html.Button]
    val status = byId("status")

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
            // The one thing the host hears. A call the page makes is invisible to the model
            // otherwise, so without this the agent would keep offering to configure a location
            // that is already done.
            Bridge.tellTheModel(
              s"Configuré las credenciales de UniFi para la location ${config.slug}.")
    )
  end wire

  final private case class Outcome(ok: Boolean, message: String)

  /** The fields, named rather than scraped off the form.
    *
    * `URLSearchParams` does not take a `FormData` in the typed binding, and listing them has its
    * own merit: the request carries exactly what the server reads, so a stray input added to the
    * markup cannot arrive as a field nobody meant to send.
    */
  private val Fields = List(
    "host",
    "port",
    "siteId",
    "apiToken",
    "apMac",
    "redirectUrl",
    "durationMinutes")

  /** Urlencoded rather than JSON on purpose. The iframe's origin is opaque, so this is a
    * cross-origin POST; `application/x-www-form-urlencoded` is a CORS-safelisted content type and
    * goes without a preflight, which JSON would require and the server would then have to answer.
    */
  private def send(config: Config): js.Promise[Outcome] =
    val payload = new dom.URLSearchParams()
    payload.append("ticket", config.ticket)
    Fields.foreach(name => payload.append(name, input(name).value))
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

end UnifiApp
