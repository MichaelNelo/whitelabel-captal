package whitelabel.captal.mcp.apps

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Authorises one write from an MCP App page back to this server, over a POST to [[UiRoutes]] that
  * arrives outside the MCP protocol and so outside its authentication.
  *
  * It proves the request came from a page this server rendered, recently, for the location it
  * names. Not a capability of its own: it is minted only for a caller that already presented a
  * token carrying `content:write`, and can do strictly less than that token could.
  *
  * Keyed on the calling token's stored hash — per-operator, so revoking a token kills its
  * outstanding tickets and leaves everybody else's alone. Stateless: no nonce is recorded.
  */
object UiTicket:

  private val ValidFor = java.time.Duration.ofMinutes(15)

  private val Algorithm = "HmacSHA256"

  private def sign(key: String, payload: String): String =
    val mac = Mac.getInstance(Algorithm)
    mac.init(new SecretKeySpec(key.getBytes(UTF_8), Algorithm))
    Base64.getUrlEncoder.withoutPadding.encodeToString(mac.doFinal(payload.getBytes(UTF_8)))

  /** `<tokenId>.<slug>.<expiry>.<signature>`, valid for 15 minutes from `now`.
    *
    * The token id travels in the clear — the server needs it to find the key before it can check
    * anything — and the signature covers it along with the rest.
    */
  def mint(key: String, tokenId: String, slug: String, now: Instant): String =
    val expiry = now.plus(ValidFor).getEpochSecond
    val payload = s"$tokenId.$slug.$expiry"
    s"$payload.${sign(key, payload)}"

  enum Rejected:
    case Malformed
    case BadSignature
    case Expired
    case UnknownToken

  /** Which token minted a ticket, read without verifying anything — only for finding the key.
    * Nothing may be trusted on the strength of it; [[verify]] decides.
    */
  def tokenIdOf(ticket: String): Option[String] =
    ticket.split("\\.", -1).toList match
      case tokenId :: _ :: _ :: _ :: Nil if tokenId.nonEmpty =>
        Some(tokenId)
      case _ =>
        None

  /** Returns the slug the ticket was minted for.
    *
    * Checked in this order, which must not change: shape, then signature, then clock — checking
    * expiry first would answer whether a forged ticket's timestamp was plausible.
    */
  def verify(key: String, ticket: String, now: Instant): Either[Rejected, String] =
    ticket.split("\\.", -1).toList match
      case tokenId :: slug :: expiry :: signature :: Nil if tokenId.nonEmpty && slug.nonEmpty =>
        val expected = sign(key, s"$tokenId.$slug.$expiry")
        if !java.security.MessageDigest.isEqual(expected.getBytes(UTF_8), signature.getBytes(UTF_8))
        then
          Left(Rejected.BadSignature)
        else
          expiry.toLongOption match
            case Some(seconds) if seconds > now.getEpochSecond =>
              Right(slug)
            case Some(_) =>
              Left(Rejected.Expired)
            case None =>
              Left(Rejected.Malformed)
      case _ =>
        Left(Rejected.Malformed)
end UiTicket
