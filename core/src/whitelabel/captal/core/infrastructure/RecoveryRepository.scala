package whitelabel.captal.core.infrastructure

import java.time.Instant

import whitelabel.captal.core.recovery.{
  Activity,
  ErrorFingerprint,
  ErrorId,
  ErrorRecord,
  Manifest,
  Signature
}
import whitelabel.captal.core.user

/** What deciding a way out needs to know. Everything here is something the server saw; the report
  * itself is the one thing it did not.
  */
trait RecoveryRepository[F[_]]:
  def find(errorId: ErrorId): F[Option[ErrorRecord]]

  /** The signature, hashed. */
  def fingerprintOf(signature: Signature): F[ErrorFingerprint]

  /** The bundle this location serves, or `None` when it could not be read. */
  def publishedBundle(): F[Option[Manifest]]

  /** Devices other than `excluding` that reported the same error since `since`. */
  def devicesReporting(fp: ErrorFingerprint, excluding: user.DeviceId, since: Instant): F[Int]

  /** Ways out this device took since `since`. */
  def recoveriesSince(deviceId: user.DeviceId, since: Instant): F[Int]

  /** What the user did on this device: the trust they earned on it, not on any other. */
  def activity(userId: user.Id, deviceId: user.DeviceId): F[Activity]
