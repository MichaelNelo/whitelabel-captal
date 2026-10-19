package whitelabel.captal.mcp

import java.time.Instant

import whitelabel.captal.mcp.access.Tokens
import whitelabel.captal.mcp.apps.UiTicket
import zio.test.*

/** The ticket stands in for the protocol's authentication on [[UiRoutes]], which is outside MCP;
  * every assertion here is about something that would otherwise fail open.
  */
object UiTicketSpec extends ZIOSpecDefault:

  // Stands in for the calling token's stored hash, which is what the server signs with.
  private val key = Tokens.hash("ana-token")
  private val other = Tokens.hash("beto-token")
  private val tokenId = "11111111-1111-1111-1111-111111111111"
  private val now = Instant.parse("2026-01-01T00:00:00Z")

  def spec =
    suite("UiTicket")(
      test("a freshly minted ticket verifies and names its location"):
        val ticket = UiTicket.mint(key, tokenId, "cafe-centro", now)
        assertTrue(UiTicket.verify(key, ticket, now) == Right("cafe-centro"))
      ,
      test("the token that minted it can be read back before anything is verified"):
        val ticket = UiTicket.mint(key, tokenId, "cafe-centro", now)
        assertTrue(UiTicket.tokenIdOf(ticket).contains(tokenId))
      ,
      test("a ticket edited to name another location is refused"):
        val ticket = UiTicket.mint(key, tokenId, "cafe-centro", now)
        val forged = ticket.replace("cafe-centro", "bar-norte")
        assertTrue(UiTicket.verify(key, forged, now) == Left(UiTicket.Rejected.BadSignature))
      ,
      test("a ticket edited to last longer is refused"):
        val ticket = UiTicket.mint(key, tokenId, "cafe-centro", now)
        val parts = ticket.split("\\.")
        val extended = s"${parts(0)}.${parts(1)}.${parts(2).toLong + 86400}.${parts(3)}"
        assertTrue(UiTicket.verify(key, extended, now) == Left(UiTicket.Rejected.BadSignature))
      ,
      test("a ticket stops working once its window passes"):
        val ticket = UiTicket.mint(key, tokenId, "cafe-centro", now)
        assertTrue(
          UiTicket.verify(key, ticket, now.plusSeconds(14 * 60)) == Right("cafe-centro"),
          UiTicket.verify(key, ticket, now.plusSeconds(16 * 60)) == Left(UiTicket.Rejected.Expired)
        )
      ,
      test("a ticket does not verify against another operator's key"):
        val ticket = UiTicket.mint(key, tokenId, "cafe-centro", now)
        assertTrue(UiTicket.verify(other, ticket, now) == Left(UiTicket.Rejected.BadSignature))
      ,
      // The key is derived from the token, so revocation reaches tickets already in the wild.
      test("rewriting the ticket to name another token does not make it verify"):
        val ticket = UiTicket.mint(key, tokenId, "cafe-centro", now)
        val swapped = ticket.replace(tokenId, "22222222-2222-2222-2222-222222222222")
        assertTrue(
          UiTicket.tokenIdOf(swapped).contains("22222222-2222-2222-2222-222222222222"),
          UiTicket.verify(key, swapped, now) == Left(UiTicket.Rejected.BadSignature))
      ,
      test("garbage is rejected rather than throwing"):
        assertTrue(
          UiTicket.verify(key, "", now) == Left(UiTicket.Rejected.Malformed),
          UiTicket.verify(key, "nonsense", now) == Left(UiTicket.Rejected.Malformed),
          UiTicket.verify(key, "a.b.c", now) == Left(UiTicket.Rejected.Malformed),
          UiTicket.verify(key, "a.b.c.d.e", now) == Left(UiTicket.Rejected.Malformed),
          UiTicket.tokenIdOf("nonsense").isEmpty
        )
      ,
      test("the ticket carries no secret"):
        val ticket = UiTicket.mint(key, tokenId, "cafe-centro", now)
        assertTrue(
          !ticket.contains(key),
          !ticket.contains("ana-token"),
          ticket.startsWith(s"$tokenId.cafe-centro."))
    )
end UiTicketSpec
