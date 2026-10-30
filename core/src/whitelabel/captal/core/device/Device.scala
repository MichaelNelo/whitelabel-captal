package whitelabel.captal.core.device

import java.time.Instant

import whitelabel.captal.core.user

/** What the network said about the device a session runs on, both from UniFi's redirect. The click
  * id comes from the link shortener in front of the portal, which tracks devices; the MAC is the
  * one UniFi will authorize.
  */
final case class Fingerprint(clientMac: String, clickId: String)

/** Whether a known device is the one in front of us.
  *
  * Inline, so a repository can splice it into its query and have the database find the device:
  * Quill reduces it to a `WHERE` at compile time. The same method runs as plain Scala everywhere
  * else. It has to be called on a concrete policy — through the trait the compiler cannot inline it
  * — which is what [[Policy]] is for: configuration names one, and the repository matches on it.
  */
trait Matching:
  inline def matches(inline known: Fingerprint, inline seen: Fingerprint): Boolean

object Matching:
  object ByClickId extends Matching:
    inline def matches(inline known: Fingerprint, inline seen: Fingerprint): Boolean =
      known.clickId == seen.clickId

  /** The MAC validates itself: access is granted to the session's MAC, so claiming another one
    * authorizes somebody else's device.
    */
  object ByMac extends Matching:
    inline def matches(inline known: Fingerprint, inline seen: Fingerprint): Boolean =
      known.clientMac == seen.clientMac

  object ByMacAndClickId extends Matching:
    inline def matches(inline known: Fingerprint, inline seen: Fingerprint): Boolean =
      known.clientMac == seen.clientMac && known.clickId == seen.clickId

/** Which [[Matching]] is in force, as configuration names it. */
enum Policy:
  case ByClickId,
    ByMac,
    ByMacAndClickId

  /** Whether `seen` carries what this policy compares. One that does not cannot be matched against
    * anything — an empty click id would match every other empty one — and is a new device.
    */
  def covers(seen: Fingerprint): Boolean =
    this match
      case ByClickId =>
        seen.clickId.nonEmpty
      case ByMac =>
        seen.clientMac.nonEmpty
      case ByMacAndClickId =>
        seen.clientMac.nonEmpty && seen.clickId.nonEmpty

object Policy:
  /** When the configuration names none. */
  val Default: Policy = ByClickId

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
