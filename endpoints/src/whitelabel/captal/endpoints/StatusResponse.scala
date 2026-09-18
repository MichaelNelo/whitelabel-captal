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
    redirectUrl: Option[String] = None,
    secondsRemaining: Option[Int] = None)

object StatusResponse:
  /** Seconds left until `expiresAt`, floored at zero. `None` when there is no expiration.
    *
    * `Int` rather than `Long`, which is the same number on the wire and a different one in the
    * browser: Scala.js has no 64-bit integers, so every `Long` is emulated with a pair of 32-bit
    * ones and the emulation is 44 KB of the bundle. A grant measured in seconds has no use for the
    * range — `Int` reaches 68 years — so the ceiling below is a formality, not a limit anyone can
    * hit.
    */
  def secondsRemainingFrom(expiresAt: Option[Instant], now: Instant): Option[Int] = expiresAt.map:
    at =>
      val seconds = java.time.Duration.between(now, at).getSeconds
      math.max(0L, math.min(seconds, Int.MaxValue.toLong)).toInt

  given Encoder[StatusResponse] = deriveEncoder
  given Decoder[StatusResponse] = deriveDecoder
  given Schema[StatusResponse] = Schema.derived
