package whitelabel.captal.core.infrastructure

import whitelabel.captal.core.device.{Device, State}

/** The session's device, by where it stands with the terms of service in force. Exactly one of the
  * two finds it.
  */
trait DeviceRepository[F[_]]:
  /** The device, if it accepted the version in force. */
  def findAccepted(): F[Option[Device[State.Accepted]]]

  /** The device, if it never accepted the terms or accepted an older version. */
  def findNotAccepted(): F[Option[Device[State.NotAccepted.type]]]
