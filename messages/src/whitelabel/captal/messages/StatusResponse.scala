package whitelabel.captal.messages

import java.time.Instant

import io.circe.generic.semiauto.*
import io.circe.{Decoder, Encoder}
import whitelabel.captal.core.application.Phase

/** @param secondsRemaining
  *   server-computed seconds left of the granted access, present only while `phase == Authorized`.
  *   The client must count down from this and not from `accessExpiresAt`: behind a captive portal
  *   the device clock may be unsynced.
  */
final case class StatusResponse(
    phase: Phase,
    locale: String,
    redirectUrl: Option[String] = None,
    secondsRemaining: Option[Int] = None)

object StatusResponse:
  /** Seconds left until `expiresAt`, floored at zero and capped at `Int.MaxValue`. `None` when
    * there is no expiration. Keep it `Int`, not `Long`: Scala.js has no native 64-bit integers.
    */
  def secondsRemainingFrom(expiresAt: Option[Instant], now: Instant): Option[Int] = expiresAt.map:
    at =>
      val seconds = java.time.Duration.between(now, at).getSeconds
      math.max(0L, math.min(seconds, Int.MaxValue.toLong)).toInt

  given Encoder[StatusResponse] = deriveEncoder
  given Decoder[StatusResponse] = deriveDecoder
