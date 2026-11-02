package whitelabel.captal.client

import scala.scalajs.js

import io.circe.syntax.*
import io.circe.{Decoder, Encoder, parser}
import org.scalajs.dom
import whitelabel.captal.client.Promises.*
import whitelabel.captal.messages.ApiError

object ApiClient:
  /** First path segment of the page URL, the location slug. `None` in dev, where the page is served
    * at `/`.
    */
  private val firstSegment: Option[String] = dom
    .window
    .location
    .pathname
    .split("/")
    .find(_.nonEmpty)
    .filter(_ != "api")

  private val apiBase: String = firstSegment.fold("/api")(slug => s"/$slug/api")

  /** Where a paused location's guest is sent: the location's own address, which the edge serves as
    * the maintenance page while it is paused (`pause_location` puts that page in place of its
    * `index.html`) and as the portal again once it is resumed. Not `maintenance.html` by name: no
    * location has its own. The query string goes along, the access point's parameters with it.
    */
  private def maintenanceUrl: String =
    firstSegment.fold("/")(slug => s"/$slug/") + dom.window.location.search

  /** `status` value the load balancer answers on this location's API path while the portal is
    * paused. Must match what `pause_location` installs, in `InfraStore.PausedBody`.
    */
  private val PausedStatus = "paused"

  inline def url(path: String): String = s"$apiBase$path"

  /** What answers in place of the network: a function from the method and the path under the API's
    * base to the answer, which may take a while to come, or to `None` for one that never comes. Set
    * by [[Preview.start]] alone, which only the preview page's own build reaches, so the portal
    * carries none of it.
    */
  private[client] var standIn: Option[(String, String) => Option[js.Promise[io.circe.Json]]] = None

  /** Through the network, or answered by the [[standIn]] when there is one. */
  def fetchApi[A: Decoder](
      method: String,
      path: String,
      body: Option[io.circe.Json] = None,
      extraHeaders: Map[String, String] = Map.empty): js.Promise[Either[ApiError, A]] =
    standIn match
      case Some(answer) =>
        answer(method, path.stripPrefix(apiBase)) match
          case Some(coming) =>
            coming.map(_.as[A].left.map(e => ApiError.InternalError(e.getMessage)))
          case None =>
            new js.Promise[Either[ApiError, A]]((_, _) => ())
      case None =>
        network[A](method, path, body, extraHeaders)

  private def network[A: Decoder](
      method: String,
      path: String,
      body: Option[io.circe.Json],
      extraHeaders: Map[String, String]): js.Promise[Either[ApiError, A]] =
    val options = js.Dynamic.literal(method = method)
    val headers = js.Dynamic.literal()
    body.foreach { json =>
      headers.updateDynamic("Content-Type")("application/json")
      options.body = json.noSpaces
    }
    extraHeaders.foreach { (k, v) =>
      headers.updateDynamic(k)(v)
    }
    if js.Object.keys(headers.asInstanceOf[js.Object]).length > 0 then
      options.headers = headers
    // A rejection here is the network's: answered as a `Left`, whose error view offers a retry,
    // rather than reported as a failure of this code.
    val exchange: js.Promise[Either[ApiError, (dom.Response, String)]] =
      (
        for
          response <- dom.fetch(path, options.asInstanceOf[dom.RequestInit])
          text     <- response.text()
        yield Right((response, text))
      ).recover(e => Left(ApiError.InternalError(s"Connection lost: $e")))
    exchange.map(_.flatMap((response, text) => answer[A](response, text)))
  end network

  private def answer[A: Decoder](response: dom.Response, text: String): Either[ApiError, A] =
    if response.ok then
      parser
        .decode[A](text)
        .left
        .map: e =>
          // Our server's answer, unreadable to our own decoder: a bug, and reported as one.
          Recovery.clientError(e)
          ApiError.InternalError(e.getMessage)
    else if isPaused(text) then
      // `replace` rather than `assign`: the paused page must not enter history.
      dom.window.location.replace(maintenanceUrl)
      Left(ApiError.InternalError("This location is paused for maintenance."))
    else
      parser.decode[ApiError](text) match
        case Right(err) =>
          Left(err)
        case Left(_) =>
          Left(ApiError.InternalError(s"HTTP ${response.status}: $text"))
  end answer

  /** Whether this body is the load balancer saying the location is paused. Matches on the parsed
    * `status` field, never on the raw text.
    */
  private def isPaused(body: String): Boolean = parser
    .parse(body)
    .toOption
    .flatMap(_.hcursor.get[String]("status").toOption)
    .contains(PausedStatus)

  def get[A: Decoder](path: String): js.Promise[Either[ApiError, A]] = fetchApi("GET", path)

  def post[A: Decoder](path: String): js.Promise[Either[ApiError, A]] = fetchApi("POST", path)

  def postJson[B: Encoder, A: Decoder](path: String, body: B): js.Promise[Either[ApiError, A]] =
    fetchApi("POST", path, Some(body.asJson))

  def putJson[B: Encoder, A: Decoder](path: String, body: B): js.Promise[Either[ApiError, A]] =
    fetchApi("PUT", path, Some(body.asJson))

  /** The endpoints themselves live in [[ViewApis]], one object per view, so that the route split
    * can defer each view's routes and decoders.
    */
end ApiClient
