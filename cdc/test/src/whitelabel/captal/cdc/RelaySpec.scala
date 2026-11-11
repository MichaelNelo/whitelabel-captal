package whitelabel.captal.cdc

import java.net.ServerSocket

import whitelabel.captal.cdc.protocol.ChangeFeedFollower
import zio.*
import zio.test.*

object RelaySpec extends ZIOSpecDefault:

  /** An rqlite post: one change set per index, each with one event on `table`. */
  private def post(indexes: Long*): String =
    val groups = indexes.map(i =>
      s"""{"index":$i,"events":[{"op":"INSERT","table":"errors","after":{"id":"e$i"}}]}""")
    s"""{"node_id":"n1","payload":[${groups.mkString(",")}]}"""

  private def indexes(relayed: Chunk[Relayed]): Chunk[Any] = relayed.map:
    case Relayed.Changed(change) =>
      change.index
    case Relayed.Gap =>
      "gap"

  private def freePort: UIO[Int] = ZIO.succeed:
    val socket = ServerSocket(0)
    try socket.getLocalPort
    finally socket.close()

  /** A relay serving gRPC on `port` for as long as the scope lasts. */
  private def serving(port: Int): ZIO[Scope, Throwable, Relay] =
    for
      relay <- Relay.make(100)
      _     <- Main.serve(port, relay)
    yield relay

  /** Until `followers` subscribers follow `relay`. */
  private def untilFollowed(relay: Relay, followers: Int = 1): UIO[Unit] =
    (ZIO.sleep(20.millis) *> relay.subscribers).repeatUntil(_ >= followers).unit

  def spec =
    suite("Relay")(
      test("a change set is published once, however often rqlite sends it"):
        for
          relay  <- Relay.make(100)
          first  <- relay.receive(post(1, 2))
          again  <- relay.receive(post(2, 3))
          caught <- relay.subscribe(Some(0)).take(3).runCollect
        yield assertTrue(first == 2, again == 1, indexes(caught) == Chunk(1L, 2L, 3L))
      ,
      test("a subscriber that comes back gets what it missed, in order"):
        for
          relay  <- Relay.make(100)
          _      <- relay.receive(post(3, 1, 2))
          _      <- relay.receive(post(4, 5))
          caught <- relay.subscribe(Some(3)).take(2).runCollect
        yield assertTrue(indexes(caught) == Chunk(4L, 5L))
      ,
      test("one that missed more than the relay keeps is told so first"):
        for
          relay  <- Relay.make(2)
          _      <- relay.receive(post(1, 2, 3, 4, 5))
          caught <- relay.subscribe(Some(1)).take(3).runCollect
        yield assertTrue(indexes(caught) == Chunk("gap", 4L, 5L))
      ,
      test("a body that is not rqlite's publishes nothing"):
        for
          relay  <- Relay.make(100)
          broken <- relay.receive("{")
          noIds  <- relay.receive("""{"payload":[{"events":[]}]}""")
        yield assertTrue(broken == 0, noIds == 0)
      ,
      suite("over gRPC")(
        test("a follower gets each change set as rqlite wrote it"):
          ZIO.scoped:
            for
              port     <- freePort
              relay    <- serving(port)
              follower <- ChangeFeedFollower.follow(s"localhost:$port").take(2).runCollect.fork
              _        <- untilFollowed(relay)
              _        <- relay.receive(post(7, 8))
              events   <- follower.join
            yield assertTrue(
              events.map {
                case ChangeFeedFollower.Event.Changed(index, json, _) =>
                  s"$index:${json.contains(s""""id":"e$index"""")}"
                case ChangeFeedFollower.Event.Gap =>
                  "gap"
              } == Chunk("7:true", "8:true"))
        ,
        test("every follower gets the trace a change set was received in, to continue it"):
          val received = Map(
            "traceparent" -> "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01")
          ZIO.scoped:
            for
              port   <- freePort
              relay  <- serving(port)
              first  <- ChangeFeedFollower.follow(s"localhost:$port").take(1).runCollect.fork
              second <- ChangeFeedFollower.follow(s"localhost:$port").take(1).runCollect.fork
              _      <- untilFollowed(relay, 2)
              _      <- relay.receive(post(7), received)
              events <- first.join.zipWith(second.join)(_ ++ _)
            yield assertTrue(
              events.size == 2,
              events.forall {
                case ChangeFeedFollower.Event.Changed(_, _, context) =>
                  context == received
                case ChangeFeedFollower.Event.Gap =>
                  false
              })
        ,
        test("a follower whose relay restarted is told it may have missed changes, and carries on"):
          for
            port     <- freePort
            first    <- Scope.make
            relay    <- serving(port).provideEnvironment(ZEnvironment(first))
            follower <- ChangeFeedFollower.follow(s"localhost:$port").take(3).runCollect.fork
            _        <- untilFollowed(relay)
            _        <- relay.receive(post(1))
            // The first relay goes away, and another takes its port.
            _      <- ZIO.sleep(200.millis) *> first.close(Exit.unit)
            events <- ZIO.scoped:
              for
                again  <- serving(port)
                _      <- untilFollowed(again)
                _      <- again.receive(post(2))
                events <- follower.join
              yield events
          yield assertTrue(
            events ==
              Chunk(
                ChangeFeedFollower
                  .Event
                  .Changed(
                    1,
                    events
                      .headOption
                      .collect { case ChangeFeedFollower.Event.Changed(_, json, _) =>
                        json
                      }
                      .getOrElse("")),
                ChangeFeedFollower.Event.Gap,
                ChangeFeedFollower
                  .Event
                  .Changed(
                    2,
                    events
                      .lastOption
                      .collect { case ChangeFeedFollower.Event.Changed(_, json, _) =>
                        json
                      }
                      .getOrElse(""))
              ))
      ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(60.seconds)
    )
end RelaySpec
