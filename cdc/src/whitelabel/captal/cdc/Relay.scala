package whitelabel.captal.cdc

import zio.*
import zio.json.*
import zio.json.ast.Json

/** One change set as rqlite commits it: its Raft index, the change set itself, as rqlite wrote it,
  * on one line, the trace it was received in, as W3C trace context headers, and the tables its
  * events touch, whose topics it is published to.
  */
final case class Change(
    index: Long,
    json: String,
    context: Map[String, String] = Map.empty,
    tables: List[String] = Nil)

/** Receives rqlite's change data capture and publishes each change set to its tables' topics,
  * through `publish`.
  *
  * It keeps nothing: every change set of every post is published, a retry's and a resent one's
  * included, and in whatever order rqlite sends them. rqlite delivers at least once and, after a
  * leader change, out of order; a subscriber absorbs both, since it reads what it acts on from the
  * database. So two relays can run at once, as they do while one replaces the other.
  */
final class Relay(publish: List[Change] => Task[Unit]):
  /** The change sets of one post, published in index order, each carrying `context`, the trace the
    * post was received in; the number published. Fails when a topic refuses one, and rqlite posts
    * it again.
    */
  def receive(body: String, context: Map[String, String] = Map.empty): Task[Int] =
    val changes = Relay
      .changesOf(body)
      .distinctBy(_.index)
      .sortBy(_.index)
      .map(_.copy(context = context))
    publish(changes).when(changes.nonEmpty).as(changes.size)
end Relay

object Relay:
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
        val tables =
          field(group, "events")
            .flatMap(_.asArray)
            .toList
            .flatten
            .flatMap(event => field(event, "table").flatMap(_.asString))
            .distinct
        field(group, "index")
          .flatMap(_.asNumber)
          .map(n => Change(n.value.longValue, group.toJson, tables = tables))
  end changesOf
end Relay
