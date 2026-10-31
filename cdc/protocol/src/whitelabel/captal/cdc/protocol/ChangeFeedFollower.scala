package whitelabel.captal.cdc.protocol

import java.util.concurrent.TimeUnit

import io.grpc.ManagedChannelBuilder
import scalapb.zio_grpc.ZManagedChannel
import zio.*
import zio.stream.ZStream

/** Following the relay: every change set rqlite commits, for as long as the stream is consumed.
  *
  * It never fails and never ends. A dropped connection is opened again, resuming after the last
  * change set seen. What may have been dropped meanwhile arrives as
  * [[ChangeFeedFollower.Event.Gap]]: more than the relay keeps, or a relay that restarted, which
  * the instance on each delivery tells. A subscriber rebuilds from the database whatever it keeps
  * from the changes, and carries on.
  */
object ChangeFeedFollower:
  enum Event:
    /** One change set, exactly as rqlite posted it: `{"index": n, "events": [...]}`. */
    case Changed(index: Long, json: String)

    /** Change sets may be missing. */
    case Gap

  val RetryAfter: Duration = 2.seconds

  /** A quiet stream is pinged this often, so a relay that is gone is found out and left. */
  val KeepAlive: Duration = 30.seconds

  /** @param address
    *   `host:port` of the relay's gRPC server.
    *
    * Each attempt opens a channel of its own, and whatever ends it is logged before the next: a
    * failure to build the channel, a defect included, would otherwise end the stream, and the fiber
    * following it, with nothing said.
    */
  def follow(address: String): ZStream[Any, Nothing, Event] =
    val (host, port) = hostAndPort(address)
    ZStream.unwrap:
      Ref
        .make(Cursor(None, None))
        .map: cursor =>
          val once = ZStream
            .unwrapScoped(
              ZioCdc
                .ChangeFeedClient
                .scoped(
                  ZManagedChannel(
                    ManagedChannelBuilder
                      .forAddress(host, port)
                      .usePlaintext()
                      .keepAliveTime(KeepAlive.toSeconds, TimeUnit.SECONDS)
                      .keepAliveWithoutCalls(true)))
                .map(client =>
                  ZStream.unwrap(
                    cursor.get.map(at => client.subscribe(SubscribeRequest(since = at.last))))))
            .mapZIO(delivery => cursor.modify(_.read(delivery)))
            .flattenIterables
            .catchAllCause(cause =>
              ZStream
                .fromZIO(
                  ZIO.logWarning(s"Change feed at $address dropped: ${cause.squash.getMessage}"))
                .drain)
          (once ++ ZStream.fromZIO(ZIO.sleep(RetryAfter)).drain).forever
  end follow

  private def hostAndPort(address: String): (String, Int) =
    address.lastIndexOf(':') match
      case -1 =>
        throw IllegalArgumentException(s"The change feed's address is host:port, not '$address'")
      case at =>
        (address.take(at), address.drop(at + 1).toInt)

  /** The relay last heard from, and the last change set seen from it. */
  final private case class Cursor(instance: Option[String], last: Option[Long]):
    def read(delivery: Delivery): (List[Event], Cursor) =
      val restarted = instance.exists(_ != delivery.instance) && last.isDefined
      val now = copy(instance = Some(delivery.instance))
      val events =
        delivery.kind match
          case Delivery.Kind.Change(change) =>
            (List(Event.Changed(change.index, change.json)), now.copy(last = Some(change.index)))
          case Delivery.Kind.Gap(_) =>
            (List(Event.Gap), now)
          case Delivery.Kind.Empty =>
            (Nil, now)
      if restarted then
        (Event.Gap :: events._1, events._2)
      else
        events
end ChangeFeedFollower
