package whitelabel.captal.api

import zio.*
import zio.http.RoutePattern
import zio.http.codec.PathCodec

/** The path every route hangs under: `/api/...`, or `/<slug>/api/...` when a location is named.
  *
  * Must be applied while each `Endpoint` is still being built: nesting the assembled `Routes`
  * rewrites the `RoutePattern` but not the endpoint's codec, so a prefixed request matches the
  * route and then fails to decode.
  */
final case class ApiBase(slug: Option[String]):
  def apply[A](pattern: RoutePattern[A]): RoutePattern[A] =
    slug.fold(pattern)(s => pattern.nest(PathCodec.literal(s)))

object ApiBase:
  /** Unprefixed: what a server with no `LOCATION_SLUG` serves. */
  val none: ApiBase = ApiBase(None)

  val layer: ZLayer[ServerSettings, Nothing, ApiBase] = ZLayer.fromFunction((s: ServerSettings) =>
    ApiBase(s.locationSlug))
end ApiBase
