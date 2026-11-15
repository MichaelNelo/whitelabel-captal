package whitelabel.captal.core.location

import cats.data.NonEmptyChain
import whitelabel.captal.core.device.Mac

/** One of a location's access points, and the controller that authorizes the guests who join
  * through it. A location has one access point per MAC.
  */
final case class AccessPoint(mac: Mac, controller: Controller)

/** The UniFi controller an access point answers to: where it is (`host`, and `port` when it is not
  * the default), which of its sites, and the API token it takes.
  */
final case class Controller(host: String, port: Option[Int], siteId: String, token: Secret)

object Controller:
  /** The controller these describe, trimmed, or every reason they do not: a blank host or site, a
    * port outside 1–65535.
    */
  def of(
      host: String,
      port: Option[Int],
      siteId: String,
      token: Secret): Either[NonEmptyChain[Error], Controller] =
    val problems =
      List(
        Option.when(host.trim.isEmpty)(Error.BlankHost),
        Option.when(siteId.trim.isEmpty)(Error.BlankSiteId),
        port.filter(p => p < 1 || p > 65535).map(Error.InvalidPort(_))
      ).flatten
    NonEmptyChain.fromSeq(problems).toLeft(Controller(host.trim, port, siteId.trim, token))

/** A value that never shows: `toString` redacts it, so it can travel inside commands and events,
  * which logs print. [[reveal]] reads it, only to send it where it goes.
  */
final class Secret private (value: String):
  def reveal: String = value

  override def toString: String = "Secret(***)"

  override def equals(other: Any): Boolean =
    other match
      case that: Secret =>
        that.reveal == value
      case _ =>
        false

  override def hashCode: Int = value.hashCode

object Secret:
  /** `text` trimmed, unless that leaves nothing. */
  def parse(text: String): Option[Secret] = Option(text.trim).filter(_.nonEmpty).map(new Secret(_))
