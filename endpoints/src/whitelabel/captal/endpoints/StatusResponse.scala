package whitelabel.captal.endpoints

import java.time.Instant

import io.circe.generic.semiauto.*
import io.circe.{Decoder, Encoder}
import sttp.tapir.Schema
import whitelabel.captal.core.application.Phase
import whitelabel.captal.endpoints.schemas.given

/** @param secondsRemaining
  *   server-computed seconds left of the granted access, present only while `phase == Authorized`.
  *
  * The client uses this rather than deriving it from `accessExpiresAt`, so its countdown never
  * depends on the device clock. That matters here more than in a normal web app: this is a captive
  * portal, so the device has had no internet yet and may not have synced NTP — a phone that just
  * booted can be minutes off.
  */
final case class StatusResponse(
    phase: Phase,
    locale: String,
    accessExpiresAt: Option[Instant] = None,
    redirectUrl: Option[String] = None,
    secondsRemaining: Option[Long] = None)

object StatusResponse:
  /** Seconds left until `expiresAt`, floored at zero. `None` when there is no expiration. */
  def secondsRemainingFrom(expiresAt: Option[Instant], now: Instant): Option[Long] = expiresAt.map(
    at => math.max(0L, java.time.Duration.between(now, at).getSeconds))

  given Encoder[StatusResponse] = deriveEncoder
  given Decoder[StatusResponse] = deriveDecoder
  given Schema[StatusResponse] = Schema.derived
