package whitelabel.captal.api

import zio.*
import zio.http.RoutePattern
import zio.http.codec.PathCodec

/** The path every route hangs under: `/api/...`, or `/<slug>/api/...` when a location is named.
  *
  * Applied when each `Endpoint` is built, which is the part that took a live bug to learn.
  * `Routes#nest` looked like the way to do this centrally — one call in `HttpApp`, every route
  * prefixed — and it rewrites the `RoutePattern` only. An `Endpoint`'s handler decodes the request
  * against the pattern the *endpoint* carries, so a nested route matched `/<slug>/api/health` and
  * then answered
  *
  *     400 Malformed path /cafe-centro/api/health failed to decode using /api/health
  *
  * while the unprefixed `/api/health` kept returning 200. Both wrong, and in the direction that
  * matters: `Deploy.healthCheckPath` registers each location's target group against
  * `/<slug>/api/health`, so every task would have been unhealthy.
  *
  * Nesting the pattern *before* it becomes an `Endpoint` fixes it, because then there is only one
  * pattern and the codec is built from it. Which is why this is a constructor parameter of every
  * route class rather than a combinator applied to the assembled `Routes` — the same
  * construction-time dependency tapir had, for the same reason.
  *
  * Nothing in the suite covers this: `TestLayers` configures no slug, so `ApiBase.none` is what the
  * tests exercise and the prefixed form only appears when `LOCATION_SLUG` is set. It is checked by
  * starting the dev server and asking for both paths.
  */
final case class ApiBase(slug: Option[String]):
  def apply[A](pattern: RoutePattern[A]): RoutePattern[A] = slug.fold(pattern)(s =>
    pattern.nest(PathCodec.literal(s)))

object ApiBase:
  /** Unprefixed, which is what a server with no `LOCATION_SLUG` serves. */
  val none: ApiBase = ApiBase(None)

  val layer: ZLayer[ServerSettings, Nothing, ApiBase] = ZLayer.fromFunction(
    (s: ServerSettings) => ApiBase(s.locationSlug))
end ApiBase
