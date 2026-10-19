package whitelabel.captal.api.routes

import whitelabel.captal.api.ApiBase
import zio.*
import zio.http.endpoint.Endpoint
import zio.http.{Method, Routes}
import zio.schema.{DeriveSchema, Schema}

/** The load balancer's health check: `GET /api/health` answering `{"status":"ok"}`. Nothing in
  * `client` reads the response.
  */
object HealthRoutes:
  final case class HealthResponse(status: String)

  object HealthResponse:
    given Schema[HealthResponse] = DeriveSchema.gen[HealthResponse]

  /** Takes the base as a parameter: this route class has no other state. */
  def routes(base: ApiBase): Routes[Any, Nothing] =
    val health = Endpoint(base(Method.GET / "api" / "health")).out[HealthResponse]
    Routes(health.implement(_ => ZIO.succeed(HealthResponse("ok"))))
end HealthRoutes
