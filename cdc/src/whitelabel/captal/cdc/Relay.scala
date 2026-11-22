package whitelabel.captal.cdc

import java.util.UUID

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.ZStream

/** One change set as rqlite commits it: its Raft index, the change set itself, as rqlite wrote it,
  * on one line, the trace it was received in, as W3C trace context headers, and the tables its
  * events touch, whose topics it is published to.
  */
final case class Change(
    index: Long,
    json: String,
    context: Map[String, String] = Map.empty,
    tables: List[String] = Nil)

/** What a subscriber is handed. */
enum Relayed:
  case Changed(change: Change)

  /** Changes this subscriber had not seen were dropped: whatever it keeps from them, it has to
    * rebuild from the database.
    */
  case Gap

/** Receives rqlite's change data capture and hands every change set to every subscriber: to its
  * table's topics, through `publish`, and to whoever follows it over gRPC.
  *
  * rqlite delivers at least once, so a change set is published once per index: one at or below the
  * last published is a retry, and is dropped. The last `capacity` are kept, so a subscriber that
  * reconnects with the last index it saw gets what it missed in between; one that missed more than
  * that is told so with a [[Relayed.Gap]]. A relay that restarted kept nothing of the one before,
  * which is why it has an `instance`: a subscriber that finds it changed knows it may have missed
  * changes, and takes it as a gap too.
  */
final class Relay private (
    hub: Hub[Change],
    state: Ref[Relay.State],
    oneAtATime: Semaphore,
    following: Ref[Int],
    capacity: Int,
    publish: List[Change] => Task[Unit],
    val instance: String):

  /** How many subscribers are following right now. */
  def subscribers: UIO[Int] = following.get

  /** The change sets of one post, published in index order, each carrying `context`, the trace the
    * post was received in. The number published: the ones not seen before.
    *
    * Published to the topics first, and only then taken as seen: a post whose change sets the topics
    * refused fails, rqlite posts it again, and they are published then.
    */
  def receive(body: String, context: Map[String, String] = Map.empty): Task[Int] = oneAtATime
    .withPermit:
      for
        s <- state.get
        fresh = Relay
          .changesOf(body)
          .filter(_.index > s.last)
          .distinctBy(_.index)
          .sortBy(_.index)
          .map(_.copy(context = context))
        _ <- publish(fresh).when(fresh.nonEmpty)
        _ <- state.set:
          val all = s.buffer ++ fresh
          val (evicted, kept) = all.splitAt(math.max(0, all.size - capacity))
          Relay.State(
            kept,
            fresh.lastOption.fold(s.last)(_.index),
            evicted.lastOption.fold(s.evictedUpTo)(_.index))
        _ <- hub.publishAll(fresh)
      yield fresh.size

  /** Every change set from now on, and before them those after `since` this relay still keeps. */
  def subscribe(since: Option[Long]): ZStream[Any, Nothing, Relayed] = ZStream.unwrapScoped:
    for
      // Subscribed before reading what is kept, so a change set published in between arrives one
      // way or the other, and the index filter drops the copy.
      live <- hub.subscribe
      _    <- ZIO.acquireRelease(following.update(_ + 1))(_ => following.update(_ - 1))
      s    <- state.get
    yield
      val missed = since.exists(_ < s.evictedUpTo)
      val replay = since.fold(Vector.empty[Change])(after => s.buffer.filter(_.index > after))
      val seen = replay.lastOption.map(_.index).orElse(since).getOrElse(s.last)
      ZStream.fromIterable(Option.when(missed)(Relayed.Gap)) ++
        ZStream.fromIterable(replay.map(Relayed.Changed(_))) ++
        ZStream.fromQueue(live).filter(_.index > seen).map(Relayed.Changed(_))
end Relay

object Relay:
  final case class State(buffer: Vector[Change], last: Long, evictedUpTo: Long)

  /** @param publish
    *   hands the change sets to their tables' topics; nothing, where there are none.
    */
  def make(capacity: Int, publish: List[Change] => Task[Unit] = _ => ZIO.unit): UIO[Relay] =
    for
      hub       <- Hub.sliding[Change](capacity)
      state     <- Ref.make(State(Vector.empty, 0L, 0L))
      gate      <- Semaphore.make(1)
      following <- Ref.make(0)
    yield Relay(hub, state, gate, following, capacity, publish, UUID.randomUUID.toString)

  /** The change sets of an rqlite post, `{"node_id": …, "payload": [{"index": n, "events": […]}]}`,
    * each with the tables its events name. A body that is not one has none: refusing it would only
    * have rqlite retry it.
    */
  def changesOf(body: String): List[Change] =
    def field(json: Json, name: String): Option[Json] = json.asObject.flatMap(_.get(name))
    body
      .fromJson[Json]
      .toOption
      .flatMap(field(_, "payload"))
      .flatMap(_.asArray)
      .toList
      .flatten
      .flatMap: group =>
        val tables = field(group, "events")
          .flatMap(_.asArray)
          .toList
          .flatten
          .flatMap(event => field(event, "table").flatMap(_.asString))
          .distinct
        field(group, "index")
          .flatMap(_.asNumber)
          .map(n => Change(n.value.longValue, group.toJson, tables = tables))
end Relay
