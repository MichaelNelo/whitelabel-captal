package whitelabel.captal.client

import scala.scalajs.js

import io.circe.syntax.*
import io.circe.{Decoder, Encoder, parser}
import org.scalajs.dom
import whitelabel.captal.client.Promises.*
import whitelabel.captal.messages.ApiError

object ApiClient:
  /** API base path. In production the page is served at `/<slug>/index.html`, so we extract the
    * first path segment and prefix all API calls with `/<slug>/api`. In dev the page is served at
    * `/` and there is no prefix segment, so we fall back to `/api`.
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

  /** What the load balancer answers on this location's API path while the portal is paused.
    *
    * A paused location has no task running at all, so this does not come from the API — the rule in
    * front of it answers directly. Which is the point: it is the one failure the portal can tell
    * apart from an outage, because an outage does not say anything.
    *
    * The value has to match what `pause_location` installs, in `InfraStore.PausedBody`. There is no
    * schema between them: one is a string in a load balancer rule and the other is this.
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
        // `replace` rather than `assign`, so the paused page is not left in history: the back
        // button would otherwise return here and bounce straight out again.
        dom.window.location.replace(maintenanceUrl)
        Left(ApiError.InternalError("This location is paused for maintenance."))
      else
        parser.decode[ApiError](text) match
          case Right(err) =>
            Left(err)
          case Left(_) =>
            Left(ApiError.InternalError(s"HTTP ${response.status}: $text"))
  end fetchApi

  /** Whether this body is the load balancer saying the location is paused.
    *
    * Checked on the parsed field rather than by matching the string, so a 503 that merely happens
    * to contain the word — an error page, a proxy's own message — is not mistaken for one. Every
    * other failure keeps going down the path it did before: a paused location is a specific answer,
    * and everything else is still an error to report.
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

  /** The endpoints themselves live in [[ViewApis]], one object per view.
    *
    * Not a style choice: a single object referenced from all six views is reachable from startup,
    * and so is everything it touches — every route and the decoder of every response type. Split by
    * view, what only one view calls is only reachable from that view, which is what lets the route
    * split defer it. Measured, that moved 22 KB gzipped out of the first paint.
    */
end ApiClient
