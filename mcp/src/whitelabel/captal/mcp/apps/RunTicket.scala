package whitelabel.captal.mcp.apps

import java.nio.charset.StandardCharsets.UTF_8

/** Lets the run page read one deploy run's progress from [[RunRoutes]], which is outside MCP and so
  * outside its authentication.
  *
  * Bound to one run and with no expiry: what it opens is read-only, and the page can be reopened
  * whenever the conversation is. Keyed on the stored hash of the token that made the call, like
  * [[UiTicket]], so revoking that token stops its tickets. Signed over a `run.` prefix, so no
  * [[UiTicket]] can pass for one.
  */
object RunTicket:

  /** `<tokenId>.<runId>.<signature>`. */
  def mint(key: String, tokenId: String, runId: String): String =
    val payload = s"$tokenId.$runId"
    s"$payload.${UiTicket.sign(key, s"run.$payload")}"

  /** Which token minted a ticket, read without verifying anything — only for finding the key. */
  def tokenIdOf(ticket: String): Option[String] =
    ticket.split("\\.", -1).toList match
      case tokenId :: _ :: _ :: Nil if tokenId.nonEmpty =>
        Some(tokenId)
      case _ =>
        None

  /** Returns the run the ticket was minted for. */
  def verify(key: String, ticket: String): Either[UiTicket.Rejected, String] =
    ticket.split("\\.", -1).toList match
      case tokenId :: runId :: signature :: Nil if tokenId.nonEmpty && runId.nonEmpty =>
        val expected = UiTicket.sign(key, s"run.$tokenId.$runId")
        if java.security.MessageDigest.isEqual(expected.getBytes(UTF_8), signature.getBytes(UTF_8))
        then
          Right(runId)
        else
          Left(UiTicket.Rejected.BadSignature)
      case _ =>
        Left(UiTicket.Rejected.Malformed)
end RunTicket
