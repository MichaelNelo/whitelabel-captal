package whitelabel.captal.mcp

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Authorises one write from an MCP App page back to this server.
  *
  * The pages this server renders are the only way a secret reaches it without passing through the
  * conversation. A tool argument would not do: the host records every `tools/call`, so a UniFi
  * token handed over that way lands in the model's context, in whatever the host persists, and in
  * any telemetry along the way. So the page POSTs the secret straight to [[UiRoutes]], the same
  * shape the upload page uses to PUT a file to S3, and the model only sees that something was
  * configured.
  *
  * That POST arrives outside the MCP protocol and therefore outside its authentication, which is
  * what this is for: it proves the request came from a page this server rendered, recently, for the
  * location it names. It is not a capability of its own — it is minted only for a caller that
  * already presented a token carrying `content:write`, and it can do strictly less than that token
  * could.
  *
  * Keyed on the calling token's stored hash. That hash never leaves this server, is stable for the
  * life of the token, and is per-operator — so revoking someone's token kills their outstanding
  * tickets, and issuing anybody else's leaves them alone. An earlier version derived one key from
  * the whole set of operator secrets and had the opposite property: every token issued invalidated
  * every page open at that moment, which stopped being hypothetical the moment tokens moved into
  * the database and operators could mint them.
  *
  * Stateless otherwise. A nonce table would have to be shared, and this server is deliberately
  * stateless so it can sit behind an ALB with more than one task; the only lookup is the token the
  * ticket names, which the request would be doing anyway.
  */
object UiTicket:

  /** Long enough to fill in a form after reading a page, short enough that a ticket which leaked
    * into a transcript is spent before anyone could act on it.
    */
  private val ValidFor = java.time.Duration.ofMinutes(15)

  private val Algorithm = "HmacSHA256"

  private def sign(key: String, payload: String): String =
    val mac = Mac.getInstance(Algorithm)
    mac.init(new SecretKeySpec(key.getBytes(UTF_8), Algorithm))
    Base64.getUrlEncoder.withoutPadding.encodeToString(mac.doFinal(payload.getBytes(UTF_8)))

  /** `<tokenId>.<slug>.<expiry>.<signature>`.
    *
    * The token id travels in the clear because the server needs it to find the key before it can
    * check anything, and it is an opaque identifier rather than a secret. The signature covers it
    * along with the rest, so naming a different token only produces a ticket that fails against
    * that token's key.
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

  /** Which token minted a ticket, read without verifying anything.
    *
    * Only for finding the key. Nothing may be trusted on the strength of this — [[verify]] decides,
    * and it re-derives the payload rather than reusing what was parsed here.
    */
  def tokenIdOf(ticket: String): Option[String] =
    ticket.split("\\.", -1).toList match
      case tokenId :: _ :: _ :: _ :: Nil if tokenId.nonEmpty =>
        Some(tokenId)
      case _ =>
        None

  /** Returns the slug the ticket was minted for.
    *
    * Checked in this order: shape, then signature, then clock. Checking expiry first would answer
    * whether a forged ticket's timestamp was plausible.
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
