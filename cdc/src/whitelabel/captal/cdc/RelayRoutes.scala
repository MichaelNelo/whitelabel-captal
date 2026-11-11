package whitelabel.captal.cdc

import io.opentelemetry.api.trace.SpanKind
import whitelabel.captal.telemetry.{Telemetry, Tracing}
import zio.*
import zio.http.*

object RelayRoutes:
  /** Each post from rqlite starts a trace, and every change set in it carries that trace on to the
    * subscribers it is handed to. rqlite sends no trace of its own: the write that caused the
    * change set is in another trace, which nothing here can name.
    */
  def apply(relay: Relay): Routes[Tracing, Nothing] = Routes(
    Method.POST / "cdc" ->
      handler: (request: Request) =>
        request
          .body
          .asString
          .orElseSucceed("")
          .flatMap: body =>
            Telemetry.span("cdc ingest", SpanKind.SERVER):
              Telemetry
                .context
                .flatMap(relay.receive(body, _))
                .tap(fresh => Telemetry.setAttribute("cdc.change_sets", fresh.toLong))
          .as(Response.ok),
    Method.GET / "health" -> handler(Response.text("ok"))
  )
end RelayRoutes
