package whitelabel.captal.mcp

import java.time.Instant

import whitelabel.captal.mcp.access.Tokens
import whitelabel.captal.mcp.apps.{RunTicket, UiTicket}
import zio.test.*

/** The run ticket stands in for the protocol's authentication on [[RunRoutes]]; every assertion
  * here is about something that would otherwise fail open.
  */
object RunTicketSpec extends ZIOSpecDefault:

  private val key = Tokens.hash("ana-token")
  private val other = Tokens.hash("beto-token")
  private val tokenId = "11111111-1111-1111-1111-111111111111"
  private val runId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"

  def spec =
    suite("RunTicket")(
      test("a minted ticket verifies and names its run"):
        assertTrue(RunTicket.verify(key, RunTicket.mint(key, tokenId, runId)) == Right(runId))
      ,
      test("the token that minted it can be read back before anything is verified"):
        assertTrue(RunTicket.tokenIdOf(RunTicket.mint(key, tokenId, runId)).contains(tokenId))
      ,
      test("a ticket edited to name another run is refused"):
        val forged = RunTicket
          .mint(key, tokenId, runId)
          .replace(runId, "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        assertTrue(RunTicket.verify(key, forged) == Left(UiTicket.Rejected.BadSignature))
      ,
      test("a ticket does not verify against another operator's key"):
        assertTrue(
          RunTicket.verify(other, RunTicket.mint(key, tokenId, runId)) ==
            Left(UiTicket.Rejected.BadSignature))
      ,
      // The same key signs both kinds, so the prefix is all that keeps one from passing for the
      // other.
      test("a UniFi ticket does not pass for a run ticket"):
        val unifi = UiTicket.mint(key, tokenId, "cafe-centro", Instant.now)
        assertTrue(RunTicket.verify(key, unifi).isLeft)
      ,
      test("garbage is rejected rather than throwing"):
        assertTrue(
          RunTicket.verify(key, "") == Left(UiTicket.Rejected.Malformed),
          RunTicket.verify(key, "a.b") == Left(UiTicket.Rejected.Malformed),
          RunTicket.tokenIdOf("nothing").isEmpty
        )
    )
end RunTicketSpec
