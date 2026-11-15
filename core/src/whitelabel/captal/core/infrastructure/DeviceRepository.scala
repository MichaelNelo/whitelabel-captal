package whitelabel.captal.core.infrastructure

import whitelabel.captal.core.device.{Device, Signals, State}
import whitelabel.captal.core.user

/** The session's device, by where it stands with the terms of service in force. Exactly one of the
  * two finds it.
  */
trait DeviceRepository[F[_]]:
  /** The device, if it accepted the version in force. */
  def findAccepted(): F[Option[Device[State.Accepted]]]

  /** The device, if it never accepted the terms or accepted an older version. */
  def findNotAccepted(): F[Option[Device[State.NotAccepted.type]]]

/** Devices by what tells them apart, outside any session: what entering the portal reads, before
  * the guest has one. Each answers the device by where it stands with the terms of service in
  * force.
  */
trait DeviceLookup[F[_]]:
  /** The known device `seen` matches under the deployment's policy; none when none does, or when
    * `seen` lacks what the policy compares.
    */
  def matching(seen: Signals): F[Option[Device[State]]]

  def byId(id: user.DeviceId): F[Option[Device[State]]]
