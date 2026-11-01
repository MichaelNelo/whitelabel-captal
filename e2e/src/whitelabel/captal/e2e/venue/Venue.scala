package whitelabel.captal.e2e.venue

import whitelabel.captal.e2e.scenario.Run
import zio.json.ast.Json

/** The venue's network, as the local configuration's stand-in for a UniFi controller sees it: what
  * every location of these features is pointed at, and what it was asked to do.
  */
object Venue:
  /** A site's id as the Integration API names it, a UUID: the page warns that the legacy name does
    * not work with a real controller.
    */
  val Site = "88f7af54-98f8-306a-a1c7-c9349722b1f6"
  val ApiKey = "e2e-api-key"

  /** How long a guest's access lasts, as the locations are configured unless a feature says. */
  val AccessMinutes = 60

  /** One action the controller was asked for. */
  final case class Call(mac: String, action: String, timeLimitMinutes: Option[Int], apiKey: String)

  /** Every action asked for the device `mac`, oldest first. */
  def callsFor(mac: String): List[Call] =
    val url = s"${Run.setting("E2E_UNIFI_RECORDS")}/calls?mac=${mac.toLowerCase}"
    val body = scala.io.Source.fromURL(url).mkString
    def field(call: Json, name: String) = call.asObject.flatMap(_.get(name))
    Json
      .decoder
      .decodeJson(body)
      .toOption
      .flatMap(_.asArray)
      .getOrElse(throw AssertionError(s"The controller's record was not readable: $body"))
      .toList
      .map: call =>
        Call(
          mac = field(call, "mac").flatMap(_.asString).getOrElse(""),
          action = field(call, "action").flatMap(_.asString).getOrElse(""),
          timeLimitMinutes = field(call, "timeLimitMinutes")
            .flatMap(_.asNumber)
            .map(_.value.intValue),
          apiKey = field(call, "apiKey").flatMap(_.asString).getOrElse("")
        )
  end callsFor
end Venue
