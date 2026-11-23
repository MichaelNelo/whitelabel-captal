package whitelabel.captal.mcp.apps

import whitelabel.captal.cdc.feed.ChangeFeedQueue
import zio.*
import zio.json.ast.Json

/** What a run page is told of as it happens: a run's row written, by whichever task runs it. Heard
  * from the change data capture relay on this task's own queue, beside the playbacks
  * (`Main.followChanges`), so a page open on one task follows a run another task is running.
  *
  * A change names the run it wrote and nothing more: the row is all the state a run has, and a page
  * reads it again to show it, as `get_deploy_status` does. A gap in the feed is published too,
  * since any run may have changed in it.
  */
final class RunFeed private (hub: Hub[RunFeed.Signal]):
  def publish(signal: RunFeed.Signal): UIO[Unit] = hub.publish(signal).unit

  /** Signals from now on, for as long as the scope lasts. */
  def subscribe: ZIO[Scope, Nothing, Dequeue[RunFeed.Signal]] = hub.subscribe
end RunFeed

object RunFeed:
  enum Signal:
    /** The run's row was written. */
    case Changed(runId: String)

    /** Runs may have changed that were never heard of. */
    case Gap

  /** The table a run is its row of, whose topic the backoffice follows. */
  val Table = "mcp_deploy_runs"

  /** A feed nothing publishes to but its caller: the server's own, which `Main.followChanges`
    * feeds, and the suites'.
    */
  def make: UIO[RunFeed] = Hub.sliding[Signal](256).map(RunFeed(_))

  val layer: ULayer[RunFeed] = ZLayer(make)

  /** What one delivery of the feed says: the id of each run a change set wrote — the row after an
    * insert or an update, before a delete — or a gap. Another table's change says nothing.
    */
  def signalsOf(event: ChangeFeedQueue.Event): List[Signal] =
    event match
      case ChangeFeedQueue.Event.Gap =>
        List(Signal.Gap)
      case ChangeFeedQueue.Event.Changed(_, json, _) =>
        def field(at: Json, name: String): Option[Json] = at.asObject.flatMap(_.get(name))
        Json
          .decoder
          .decodeJson(json)
          .toOption
          .flatMap(field(_, "events"))
          .flatMap(_.asArray)
          .toList
          .flatten
          .filter(event => field(event, "table").flatMap(_.asString).contains(Table))
          .flatMap(event =>
            field(event, "after")
              .orElse(field(event, "before"))
              .flatMap(field(_, "id"))
              .flatMap(_.asString))
          .distinct
          .map(Signal.Changed(_))
end RunFeed
