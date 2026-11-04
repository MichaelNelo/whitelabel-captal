package whitelabel.captal.core.device

import java.time.Instant

import whitelabel.captal.core.user

/** What the redirect said about the device a session runs on. The fingerprint comes from the link
  * shortener in front of the portal, which computes it from what the browser reports; the MAC is
  * the one UniFi will authorize.
  */
final case class Signals(clientMac: Option[Mac], fingerprint: String)

/** Whether a known device is the one in front of us.
  *
  * Inline, so a repository can splice it into its query and have the database find the device:
  * Quill reduces it to a `WHERE` at compile time. The same method runs as plain Scala everywhere
  * else. It has to be called on a concrete policy — through the trait the compiler cannot inline it
  * — which is what [[Policy]] is for: configuration names one, and the repository matches on it.
  */
trait Matching:
  inline def matches(inline known: Signals, inline seen: Signals): Boolean

object Matching:
  object ByFingerprint extends Matching:
    inline def matches(inline known: Signals, inline seen: Signals): Boolean =
      known.fingerprint == seen.fingerprint

  /** The MAC validates itself: access is granted to the session's MAC, so claiming another one
    * authorizes somebody else's device.
    */
  object ByMac extends Matching:
    inline def matches(inline known: Signals, inline seen: Signals): Boolean =
      known.clientMac == seen.clientMac

  object ByMacAndFingerprint extends Matching:
    inline def matches(inline known: Signals, inline seen: Signals): Boolean =
      known.clientMac == seen.clientMac && known.fingerprint == seen.fingerprint

/** Which [[Matching]] is in force, as configuration names it. */
enum Policy:
  case ByFingerprint,
    ByMac,
    ByMacAndFingerprint

  /** Whether `seen` carries what this policy compares. One that does not cannot be matched against
    * anything — an empty fingerprint would match every other empty one — and is a new device.
    */
  def covers(seen: Signals): Boolean =
    this match
      case ByFingerprint =>
        seen.fingerprint.nonEmpty
      case ByMac =>
        seen.clientMac.nonEmpty
      case ByMacAndFingerprint =>
        seen.clientMac.nonEmpty && seen.fingerprint.nonEmpty

object Policy:
  /** When the configuration names none. Both fields: a fingerprint alone is shared by phones of one
    * model and system, and a MAC alone changes when a phone rotates its private address.
    */
  val Default: Policy = ByMacAndFingerprint

  def fromConfig(value: Option[String]): Either[String, Policy] =
    value.map(_.trim).filter(_.nonEmpty) match
      case None =>
        Right(Default)
      case Some(name) =>
        values
          .find(_.toString == name)
          .toRight(s"Unknown device matching policy '$name'; one of ${values.mkString(", ")}")

enum Event:
  /** A user identified on a device. Emitted each time they are, not only the first: being seen
    * together again is what keeps the pair current.
    */
  case DeviceLinked(userId: user.Id, deviceId: user.DeviceId, occurredAt: Instant)

  /** The device accepted the terms of service in force, the version `termsId`. */
  case TermsAccepted(deviceId: user.DeviceId, termsId: String, occurredAt: Instant)
