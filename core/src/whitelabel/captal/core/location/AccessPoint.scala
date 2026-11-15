package whitelabel.captal.core.location

import cats.data.NonEmptyChain
import whitelabel.captal.core.device.Mac

/** One of a location's access points, the controller that authorizes the guests who join through
  * it, and what the portal does for them. A location has one access point per MAC.
  */
final case class AccessPoint(mac: Mac, controller: Controller, settings: Settings)

/** What the portal does for a guest who joins through an access point: how long they are let in for
  * (`None`, the default), where the dispatcher sends them instead of the portal, whether the
  * controller is asked to authorize them at all, and whether their redirect has to carry the link
  * shortener's fingerprint. An access point's, since each answers to its own controller and its own
  * site's portal link.
  */
final case class Settings(
    durationMinutes: Option[Int],
    redirectUrl: Option[String],
    unifiEnabled: Boolean,
    fingerprintRequired: Boolean)

object Settings:
  /** A new access point's: UniFi on, a fingerprint required, nothing else set. */
  val Default: Settings = Settings(
    durationMinutes = None,
    redirectUrl = None,
    unifiEnabled = true,
    fingerprintRequired = true)

/** A change to some of an access point's settings; `None` leaves one as it is, and `Some(None)`
  * clears the duration or the redirect URL.
  */
final case class SettingsChange(
    durationMinutes: Option[Option[Int]] = None,
    redirectUrl: Option[Option[String]] = None,
    unifiEnabled: Option[Boolean] = None,
    fingerprintRequired: Option[Boolean] = None):
  /** `settings` changed as asked, or every reason it cannot be: a duration under a minute, a
    * redirect URL that is not http or https.
    */
  def applyTo(settings: Settings): Either[NonEmptyChain[Error], Settings] =
    val redirect = redirectUrl.map(_.map(_.trim))
    val problems =
      List(
        durationMinutes.flatten.filter(_ < 1).map(Error.InvalidDuration(_)),
        redirect
          .flatten
          .filterNot(url => url.startsWith("https://") || url.startsWith("http://"))
          .map(Error.InvalidRedirectUrl(_))
      ).flatten
    NonEmptyChain
      .fromSeq(problems)
      .toLeft(
        Settings(
          durationMinutes = durationMinutes.getOrElse(settings.durationMinutes),
          redirectUrl = redirect.getOrElse(settings.redirectUrl),
          unifiEnabled = unifiEnabled.getOrElse(settings.unifiEnabled),
          fingerprintRequired = fingerprintRequired.getOrElse(settings.fingerprintRequired)
        ))
end SettingsChange

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
