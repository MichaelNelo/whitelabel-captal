package whitelabel.captal.cdc

import io.grpc.StatusException
import whitelabel.captal.cdc.protocol.{ChangeSet, Delivery, Gap, SubscribeRequest, ZioCdc}
import zio.stream.ZStream

/** `ChangeFeed.Subscribe`, the relay's one method: what [[Relay.subscribe]] hands, each delivery
  * signed with the relay's instance.
  */
final class ChangeFeedService(relay: Relay) extends ZioCdc.ChangeFeed:
  def subscribe(request: SubscribeRequest): ZStream[Any, StatusException, Delivery] = relay
    .subscribe(request.since)
    .map:
      case Relayed.Changed(change) =>
        Delivery(relay.instance, Delivery.Kind.Change(ChangeSet(change.index, change.json)))
      case Relayed.Gap =>
        Delivery(relay.instance, Delivery.Kind.Gap(Gap()))
end ChangeFeedService
