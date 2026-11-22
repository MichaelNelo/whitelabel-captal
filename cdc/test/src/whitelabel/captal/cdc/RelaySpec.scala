package whitelabel.captal.cdc

import java.net.ServerSocket

import whitelabel.captal.cdc.protocol.{ChangeFeedFollower, ChangeFeedQueue}
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
      test("a change set names the tables its events touch, each once"):
        val body =
          """{"payload":[{"index":9,"events":[{"op":"INSERT","table":"errors"},{"op":"UPDATE","table":"video_playbacks"},{"op":"DELETE","table":"errors"}]}]}"""
        assertTrue(Relay.changesOf(body).map(_.tables) == List(List("errors", "video_playbacks")))
      ,
      test("a post the topics refuse is not taken as seen, so rqlite's retry publishes it"):
        for
          refusing  <- Ref.make(true)
          published <- Ref.make(List.empty[Long])
          relay     <- Relay.make(
            100,
            changes =>
              refusing
                .get
                .flatMap(refuse =>
                  if refuse then
                    ZIO.fail(IllegalStateException("refused"))
                  else
                    published.update(_ ++ changes.map(_.index))))
          first <- relay.receive(post(1, 2)).either
          _     <- refusing.set(false)
          again <- relay.receive(post(1, 2))
          sent  <- published.get
        yield assertTrue(first.isLeft, again == 2, sent == List(1L, 2L))
      ,
      suite("topics")(
        test("a topic's change sets go in batches of ten at most, in index order"):
          val sets = (25L to 1L by -1).toList.map(i => Change(i, s"""{"index":$i}"""))
          val batches = Topics.batches(sets)
          assertTrue(
            batches.map(_.size) == List(10, 10, 5),
            batches.flatten.map(_.index) == (1L to 25L).toList)
        ,
        test("and of a bounded size, one too large alone"):
          val large = "x" * Topics.MaxBatchBytes
          val sets = List(Change(1, "a"), Change(2, large), Change(3, "b"))
          assertTrue(Topics.batches(sets).map(_.map(_.index)) == List(List(1L), List(2L), List(3L)))
        ,
        test("a change set carries its index and its trace as attributes, nothing else"):
          val change = Change(4, "{}", Map("traceparent" -> "00-a-b-01", "baggage" -> "x"))
          val attributes = Topics.attributes(change)
          assertTrue(
            attributes.keySet == Set("index", "traceparent"),
            attributes("index").dataType == "Number",
            attributes("index").stringValue == "4")
      ),
      suite("queue names")(
        test("a service's queues are named under it, within SQS's 80 characters"):
          val name = ChangeFeedQueue.namePrefix("captal-local-feed", "plaza") + ("a" * 32)
          assertTrue(
            name == s"captal-local-feed-plaza-${"a" * 32}",
            ChangeFeedQueue.isQueueOf("captal-local-feed", "plaza", name))
        ,
        test("a long slug is shortened with a digest, and still tells its queues apart"):
          val slug = "the-longest-location-slug-anyone-could-have-written"
          val name = ChangeFeedQueue.namePrefix("captal-prod-feed", slug) + ("0" * 32)
          assertTrue(
            name.length <= ChangeFeedQueue.MaxName,
            ChangeFeedQueue.isQueueOf("captal-prod-feed", slug, name),
            !ChangeFeedQueue.isQueueOf("captal-prod-feed", "the-longest", name)
          )
        ,
        test("one slug's queues are not those of a slug that extends it"):
          val other = ChangeFeedQueue.namePrefix("captal-local-feed", "x-y") + ("b" * 32)
          assertTrue(!ChangeFeedQueue.isQueueOf("captal-local-feed", "x", other))
      ),
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
