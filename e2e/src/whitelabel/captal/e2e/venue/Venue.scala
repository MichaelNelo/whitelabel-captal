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

  /** The access point a location's guests come through, and the one it is registered with: one of
    * its own, since an AP takes the guests of a single running location. Locally administered
    * (`02:`), from a digest of the slug.
    */
  def apFor(slug: String): String =
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(slug.getBytes("UTF-8"))
    ("02" :: digest.take(5).map(b => f"${b & 0xff}%02x").toList).mkString(":")

  /** How long a guest's access lasts, as the locations are configured unless a feature says. */
  val AccessMinutes = 60

  /** One action the controller was asked for. */
  final case class Call(mac: String, action: String, timeLimitMinutes: Option[Int], apiKey: String)

  /** What the load balancer answers a phone UniFi sends to its external portal server with `query`:
    * `/guest/s/default/`, over HTTP as Global Accelerator reaches it, the redirect not followed.
    * The status, and the `Location` a dispatch rule answers.
    */
  def dispatch(query: String): (Int, Option[String]) =
    val balancer = Run.setting("E2E_LOAD_BALANCER").replaceFirst("^https://", "http://")
    val client = java
      .net
      .http
      .HttpClient
      .newBuilder()
      .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
      .build()
    val response = client.send(
      java
        .net
        .http
        .HttpRequest
        .newBuilder(java.net.URI.create(s"$balancer/guest/s/default/?$query"))
        .GET()
        .build(),
      java.net.http.HttpResponse.BodyHandlers.discarding()
    )
    (response.statusCode, Option(response.headers.firstValue("Location").orElse(null)))
  end dispatch

  /** Where the load balancer sends a phone UniFi sends with `query`, waiting for the access point's
    * dispatch rule, which the backoffice writes once a run ends: the address with `query` carried
    * over. LocalStack answers `#{query}` as it is rather than the query (see the dispatch plan), so
    * the query is put back here, as AWS puts it.
    */
  def dispatched(query: String): String =
    val deadline = System.nanoTime + 60_000_000_000L
    def ask(): String =
      dispatch(query) match
        case (302, Some(location)) =>
          val address = location.replaceFirst("#.*$", "").replaceFirst("\\?.*$", "")
          s"$address?$query"
        case _ if System.nanoTime < deadline =>
          Thread.sleep(500)
          ask()
        case (status, location) =>
          throw AssertionError(
            s"The load balancer answered $status${location.fold("")(l => s" to $l")} for $query")
    ask()

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
