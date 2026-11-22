package whitelabel.captal.cdc

import zio.*
import zio.test.*

object RelaySpec extends ZIOSpecDefault:

  /** An rqlite post: one change set per index, each with one event on `table`. */
  private def post(indexes: Long*): String =
    val groups = indexes.map(i =>
      s"""{"index":$i,"events":[{"op":"INSERT","table":"errors","after":{"id":"e$i"}}]}""")
    s"""{"node_id":"n1","payload":[${groups.mkString(",")}]}"""

  /** A relay whose topics take everything, and what they took. */
  private def recording: UIO[(Relay, Ref[List[Change]])] = Ref
    .make(List.empty[Change])
    .map(sent => (Relay(changes => sent.update(_ ++ changes)), sent))

  def spec =
    suite("Relay")(
      test("a post's change sets are published in index order, each with the post's trace"):
        for
          (relay, sent) <- recording
          count         <- relay.receive(post(3, 1, 2), Map("traceparent" -> "00-t-s-01"))
          changes       <- sent.get
        yield assertTrue(
          count == 3,
          changes.map(_.index) == List(1L, 2L, 3L),
          changes.forall(_.context == Map("traceparent" -> "00-t-s-01")))
      ,
      test("it keeps nothing: a change set rqlite sends again is published again"):
        for
          (relay, sent) <- recording
          _             <- relay.receive(post(1, 2))
          again         <- relay.receive(post(2, 3))
          changes       <- sent.get
        yield assertTrue(again == 2, changes.map(_.index) == List(1L, 2L, 2L, 3L))
      ,
      test("a post the topics refuse fails, for rqlite to post it again"):
        val relay = Relay(_ => ZIO.fail(IllegalStateException("refused")))
        for refused <- relay.receive(post(1)).either
        yield assertTrue(refused.isLeft)
      ,
      test("a body that is not rqlite's publishes nothing"):
        for
          (relay, sent) <- recording
          broken        <- relay.receive("{")
          noIds         <- relay.receive("""{"payload":[{"events":[]}]}""")
          changes       <- sent.get
        yield assertTrue(broken == 0, noIds == 0, changes.isEmpty)
      ,
      test("a change set names the tables its events touch, each once"):
        val body =
          """{"payload":[{"index":9,"events":[{"op":"INSERT","table":"errors"},{"op":"UPDATE","table":"video_playbacks"},{"op":"DELETE","table":"errors"}]}]}"""
        assertTrue(Relay.changesOf(body).map(_.tables) == List(List("errors", "video_playbacks")))
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
      )
    )
end RelaySpec
