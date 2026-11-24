package whitelabel.captal.core.location

import cats.data.NonEmptyChain
import whitelabel.captal.core.device.Mac

/** One of a location's access points, the controller that authorizes the guests who join through
  * it, and what the portal does for them. A location has one access point per MAC.
  */
final case class AccessPoint(mac: Mac, controller: Controller, settings: Settings)

/** What the portal does for a guest who joins through an access point: how long they are let in for
  * (`None`, the default), where the dispatcher sends them instead of the portal, and whether the
  * controller is asked to authorize them at all. An access point's, since each answers to its own
  * controller.
  */
final case class Settings(
    durationMinutes: Option[Int],
    redirectUrl: Option[String],
    unifiEnabled: Boolean)

object Settings:
  /** A new access point's: UniFi on, nothing else set. */
  val Default: Settings = Settings(durationMinutes = None, redirectUrl = None, unifiEnabled = true)

/** A change to some of an access point's settings; `None` leaves one as it is, and `Some(None)`
  * clears the duration or the redirect URL.
  */
final case class SettingsChange(
    durationMinutes: Option[Option[Int]] = None,
    redirectUrl: Option[Option[String]] = None,
    unifiEnabled: Option[Boolean] = None):
  /** `settings` changed as asked, or every reason it cannot be: a duration under a minute, a
    * redirect URL the load balancer's redirect cannot send a guest to ([[RedirectTarget.of]]).
    */
  def applyTo(settings: Settings): Either[NonEmptyChain[Error], Settings] =
    val redirect = redirectUrl.map(_.map(_.trim))
    val problems =
      List(
        durationMinutes.flatten.filter(_ < 1).map(Error.InvalidDuration(_)),
        redirect
          .flatten
          .flatMap(url =>
            RedirectTarget.of(url).left.toOption.map(Error.InvalidRedirectUrl(url, _)))
      ).flatten
    NonEmptyChain
      .fromSeq(problems)
      .toLeft(
        Settings(
          durationMinutes = durationMinutes.getOrElse(settings.durationMinutes),
          redirectUrl = redirect.getOrElse(settings.redirectUrl),
          unifiEnabled = unifiEnabled.getOrElse(settings.unifiEnabled)
        ))
end SettingsChange

opaque type ControllerId = String

object ControllerId:
  def apply(value: String): ControllerId = value

  extension (id: ControllerId)
    def asString: String = id

/** A UniFi controller, the system's: where it is (`host`, and `port` when it is not the default),
  * which of its sites, and the API token it takes. Any access point of any location can answer to
  * it, so its token is stored once, here.
  */
final case class Controller(
    id: ControllerId,
    host: String,
    port: Option[Int],
    siteId: String,
    token: Secret):
  /** Where it is, as two controllers are told apart: a port left out is 443. */
  def address: (String, Int, String) = (host, port.getOrElse(443), siteId)

object Controller:
  /** The controller these describe, trimmed, or every reason they do not: a blank host or site, a
    * port outside 1–65535.
    */
  def of(
      id: ControllerId,
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
    NonEmptyChain.fromSeq(problems).toLeft(Controller(id, host.trim, port, siteId.trim, token))

/** The controller a new access point answers to: one the system has, or a new one, whose id the
  * caller makes as it makes every id.
  */
enum ControllerChoice:
  case Existing(id: ControllerId)
  case New(id: ControllerId, host: String, port: Option[Int], siteId: String, token: Secret)

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
