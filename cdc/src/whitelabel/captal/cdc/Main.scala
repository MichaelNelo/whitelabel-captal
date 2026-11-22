package whitelabel.captal.cdc

import whitelabel.captal.telemetry.Telemetry
import zio.*
import zio.http.Server

/** The relay: rqlite posts to it over HTTP, and it publishes each change set to its tables' topics.
  *
  * `CDC_PORT` (8090 unless set) is the HTTP port. `CDC_TOPIC_PREFIX` is a table's topic's ARN but
  * for the table's name, and required: publishing is all the relay does.
  */
object Main extends ZIOAppDefault:
  def run = ZIO.scoped:
    for
      httpPort <- System.envOrElse("CDC_PORT", "8090").map(_.toInt)
      prefix   <- System
        .env("CDC_TOPIC_PREFIX")
        .map(_.map(_.trim).filter(_.nonEmpty))
        .someOrFail(
          IllegalStateException("CDC_TOPIC_PREFIX is not set: the relay has nowhere to publish"))
      topics <- Topics.make(prefix)
      relay = Relay(topics.publish)
      _ <- ZIO.logInfo(s"Publishing rqlite's change data capture from :$httpPort to $prefix<table>")
      _ <- Server
        .serve(RelayRoutes(relay))
        .provide(Server.defaultWithPort(httpPort), Telemetry.layer("captal-cdc"))
    yield ()
end Main
