package whitelabel.captal.client

import scala.scalajs.js

import io.circe.syntax.*
import io.circe.{Decoder, Encoder, parser}
import org.scalajs.dom
import whitelabel.captal.client.Promises.*
import whitelabel.captal.endpoints.ApiError

object ApiClient:
  /** API base path. In production the page is served at `/<slug>/index.html`, so we extract the
    * first path segment and prefix all API calls with `/<slug>/api`. In dev the page is served at
    * `/` and there is no prefix segment, so we fall back to `/api`.
    */
  private val apiBase: String =
    val firstSegment = dom.window.location.pathname.split("/").find(_.nonEmpty)
    firstSegment match
      case Some(slug) if slug != "api" =>
        s"/$slug/api"
      case _ =>
        "/api"

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
      else
        parser.decode[ApiError](text) match
          case Right(err) =>
            Left(err)
          case Left(_) =>
            Left(ApiError.InternalError(s"HTTP ${response.status}: $text"))
  end fetchApi

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
