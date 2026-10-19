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

  /** Served from the same prefix as the page itself, beside `index.html`. */
  private val maintenanceUrl: String =
    firstSegment.fold("/maintenance.html")(slug => s"/$slug/maintenance.html")

  /** `status` value the load balancer answers on this location's API path while the portal is
    * paused. Must match what `pause_location` installs, in `InfraStore.PausedBody`.
    */
  private val PausedStatus = "paused"

  inline def url(path: String): String = s"$apiBase$path"

  def fetchApi[A: Decoder](
      method: String,
      path: String,
      body: Option[io.circe.Json] = None,
      extraHeaders: Map[String, String] = Map.empty): js.Promise[Either[ApiError, A]] =
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
    for
      response <- dom.fetch(path, options.asInstanceOf[dom.RequestInit])
      text     <- response.text()
    yield
      if response.ok then
        parser.decode[A](text).left.map(e => ApiError.InternalError(e.getMessage))
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
  end fetchApi

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
