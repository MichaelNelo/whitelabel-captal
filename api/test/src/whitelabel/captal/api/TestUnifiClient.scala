package whitelabel.captal.api

import whitelabel.captal.platform.{UnifiAccess, UnifiClient}
import zio.*

/** Test stub for [[UnifiClient]]: records calls instead of reaching the network, so the tests
  * exercise the production `UnifiAccessHandler` with only the transport swapped.
  *
  * Starts **inactive**, and in that state every call fails — reproducing the "UniFi is not
  * answering" path: the handler logs and the session stays in `Ready`, ready to retry.
  */
final class TestUnifiClient(
    private val active: Ref[Boolean],
    private val recorded: Ref[Chunk[TestUnifiClient.Call]])
    extends UnifiClient:
  import TestUnifiClient.*

  def authorizeGuest(unifi: UnifiAccess, clientMac: String): Task[Unit] = record(
    Call.Authorize(clientMac))

  def revokeGuestAccess(unifi: UnifiAccess, clientMac: String): Task[Unit] = record(
    Call.Revoke(clientMac))

  private def record(call: Call): Task[Unit] = active
    .get
    .flatMap:
      case false =>
        ZIO.fail(new RuntimeException("TestUnifiClient inactive — simulated UniFi failure"))
      case true =>
        recorded.update(_ :+ call)

  val enable: UIO[Unit] = active.set(true)
  val disable: UIO[Unit] = active.set(false)
  val calls: UIO[Chunk[Call]] = recorded.get
  val reset: UIO[Unit] = recorded.set(Chunk.empty)
end TestUnifiClient

object TestUnifiClient:
  enum Call:
    case Authorize(mac: String)
    case Revoke(mac: String)

  val layer: ULayer[TestUnifiClient] = ZLayer.fromZIO:
    for
      active   <- Ref.make(false)
      recorded <- Ref.make(Chunk.empty[Call])
    yield new TestUnifiClient(active, recorded)

  def enable: URIO[TestUnifiClient, Unit] = ZIO.serviceWithZIO[TestUnifiClient](_.enable)
  def disable: URIO[TestUnifiClient, Unit] = ZIO.serviceWithZIO[TestUnifiClient](_.disable)
  def calls: URIO[TestUnifiClient, Chunk[Call]] = ZIO.serviceWithZIO[TestUnifiClient](_.calls)
  def reset: URIO[TestUnifiClient, Unit] = ZIO.serviceWithZIO[TestUnifiClient](_.reset)
end TestUnifiClient
