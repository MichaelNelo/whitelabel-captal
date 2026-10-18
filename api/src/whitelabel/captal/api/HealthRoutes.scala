package whitelabel.captal.api

import zio.*
import zio.http.endpoint.Endpoint
import zio.http.{Method, Routes}
import zio.schema.{DeriveSchema, Schema}

/** The load balancer's health check, and the first route off tapir.
  *
  * zio-http's own `Endpoint` describes the same thing tapir's did — method, path, output — and the
  * description is still a value, implemented separately. What changes underneath is the codec: a
  * tapir `Schema` documents a shape that circe serialises, while here the `Schema` *is* the
  * serialiser. So one `DeriveSchema.gen` replaces both the circe instances and the tapir `Schema`
  * this file used to carry.
  *
  * Safe to start here because nothing but a health check reads the response — zero references in
  * `client`. Every other route in this module answers a type the browser decodes, and for those the
  * shape zio-schema writes has to be squared with what circe reads, which is what
  * `ClientDecodeSuite` is for.
  */
object HealthRoutes:
  final case class HealthResponse(status: String)

  object HealthResponse:
    given Schema[HealthResponse] = DeriveSchema.gen[HealthResponse]

  private val health = Endpoint(Method.GET / "api" / "health").out[HealthResponse]

  val routes: Routes[Any, Nothing] = Routes(
    health.implement(_ => ZIO.succeed(HealthResponse("ok"))))
end HealthRoutes
