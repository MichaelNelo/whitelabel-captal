package whitelabel.captal.api

import whitelabel.captal.cdc.protocol.ChangeFeedFollower
import zio.*
import zio.json.ast.Json

/** What a waiting page is told of as it happens: an error recorded anywhere, on any location, which
  * may make the one it waits on recurrent. Heard from the change data capture relay, so a page
  * learns of it from whichever server recorded it.
  *
  * Every error with a fingerprint is published, and each page checks whether it was its own: the
  * fingerprint of the error a page waits on is in the database, not in the page's request, and
  * errors are rare enough that checking on each one costs nothing. A gap in the feed is published
  * too, since an error may have been recorded in it.
  */
final class RecoveryFeed private (hub: Hub[RecoveryFeed.Signal]):
  def publish(signal: RecoveryFeed.Signal): UIO[Unit] = hub.publish(signal).unit

  /** Signals from now on, for as long as the scope lasts. */
  def subscribe: ZIO[Scope, Nothing, Dequeue[RecoveryFeed.Signal]] = hub.subscribe
end RecoveryFeed

object RecoveryFeed:
  enum Signal:
    /** An error was recorded, with the fingerprint that tells whether it is another's. */
    case Recorded(fingerprint: String)

    /** Errors may have been recorded that were never heard of. */
    case Gap

  /** A feed nothing publishes to but its caller: the suites publish to it. */
  def make: UIO[RecoveryFeed] = Hub.sliding[Signal](256).map(RecoveryFeed(_))

  /** Following the relay at `CDC_FEED_ADDRESS` (`host:port`) for as long as the server runs. A
    * warning and not a failure without it: a waiting page then learns of a recurrence when it
    * connects again, and its wait still ends.
    */
  val layer: ZLayer[Any, Nothing, RecoveryFeed] = ZLayer.scoped:
    for
      feed    <- make
      address <- System.env("CDC_FEED_ADDRESS").orDie.map(_.map(_.trim).filter(_.nonEmpty))
      _       <-
        address match
          case Some(at) =>
            ZIO.logInfo(s"Following the change feed at $at") *>
              ChangeFeedFollower
                .follow(at)
                .mapConcat(signalsOf)
                .foreach(feed.publish)
                .forkScoped
                .unit
          case None =>
            ZIO.logWarning(
              "No CDC_FEED_ADDRESS: a waiting page hears that its error recurred only when it connects again")
    yield feed

  /** What one delivery of the feed says: an `INSERT` into `errors` with a fingerprint, read from
    * the row in `after`; or a gap. Anything else, including what is not rqlite's, says nothing.
    */
  def signalsOf(event: ChangeFeedFollower.Event): List[Signal] =
    event match
      case ChangeFeedFollower.Event.Gap =>
        List(Signal.Gap)
      case ChangeFeedFollower.Event.Changed(_, json) =>
        def field(at: Json, name: String): Option[Json] = at.asObject.flatMap(_.get(name))
        Json
          .decoder
          .decodeJson(json)
          .toOption
          .flatMap(field(_, "events"))
          .flatMap(_.asArray)
          .toList
          .flatten
          .filter(event =>
            field(event, "table").flatMap(_.asString).contains("errors") &&
              field(event, "op").flatMap(_.asString).contains("INSERT"))
          .flatMap(event =>
            field(event, "after").flatMap(field(_, "fingerprint")).flatMap(_.asString))
          .map(Signal.Recorded(_))
end RecoveryFeed
