package whitelabel.captal.cdc

import zio.*
import zio.http.*

/** What rqlite reaches the relay on: HTTP, the one transport its change data capture speaks.
  *
  *   - `POST /cdc`: a change data capture post. Answered 200 for any body, as a refusal would only
  *     have rqlite retry it.
  *   - `GET /health`.
  *
  * Subscribers are served by gRPC instead ([[ChangeFeedService]]). Neither is authenticated: both
  * are reached only inside the network, by rqlite and by the services that subscribe, which the
  * relay's security group is what admits.
  */
object RelayRoutes:
  def apply(relay: Relay): Routes[Any, Nothing] = Routes(
    Method.POST / "cdc" ->
      handler: (request: Request) =>
        request.body.asString.orElseSucceed("").flatMap(relay.receive).as(Response.ok),
    Method.GET / "health" -> handler(Response.text("ok"))
  )
end RelayRoutes
