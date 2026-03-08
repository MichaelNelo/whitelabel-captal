package whitelabel.captal.api

import sttp.tapir.json.circe.*
import sttp.tapir.ztapir.*
import sttp.tapir.{PublicEndpoint, Schema}
import zio.*

object HealthRoutes:
  case class HealthResponse(status: String)

  object HealthResponse:
    import io.circe.generic.semiauto.*
    given io.circe.Encoder[HealthResponse] = deriveEncoder
    given io.circe.Decoder[HealthResponse] = deriveDecoder
    given Schema[HealthResponse] = Schema.derived

  private val healthEndpoint: PublicEndpoint[Unit, Unit, HealthResponse, Any] = endpoint
    .get
    .in("api" / "health")
    .out(jsonBody[HealthResponse])
    .description("Health check endpoint")

  val route: ZServerEndpoint[Any, Any] = healthEndpoint.zServerLogic: _ =>
    ZIO.succeed(HealthResponse("ok"))

  val routes: List[ZServerEndpoint[Any, Any]] = List(route)
end HealthRoutes
