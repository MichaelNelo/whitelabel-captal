package whitelabel.captal.core.infrastructure

import whitelabel.captal.core.device.Mac
import whitelabel.captal.core.location.{
  AccessPoint,
  Controller,
  ControllerId,
  Location,
  LocationId,
  Slug,
  State
}

trait LocationRepository[F[_]]:
  /** The location with this slug, in whatever state it is. */
  def find(slug: Slug): F[Option[Location[State]]]

  /** The location with this id, in whatever state it is. Not `find`: the slug and the id erase to
    * the same type, and two overloads would clash.
    */
  def byId(id: LocationId): F[Option[Location[State]]]

  /** The location with this id, if it is deployed. */
  def findDeployed(id: LocationId): F[Option[Location[State.Deployed]]]

trait AccessPointRepository[F[_]]:
  /** The access points the location is configured with, whatever its state. */
  def of(location: LocationId): F[List[AccessPoint]]

  /** The location that has taken the access point `mac`, deploying or deployed, if one has. */
  def takenBy(mac: Mac): F[Option[Slug]]

  /** The controller the system's access point `mac` answers to, if the system has the access point.
    */
  def controllerOf(mac: Mac): F[Option[Controller]]

/** The UniFi controllers, the system's. */
trait ControllerRepository[F[_]]:
  def find(id: ControllerId): F[Option[Controller]]

  /** The controller at this host, port — `None` is 443 — and site, if there is one. */
  def at(host: String, port: Option[Int], siteId: String): F[Option[ControllerId]]

  /** How many access points a location is configured with answer to the controller. One no location
    * is configured with goes with its controller.
    */
  def uses(id: ControllerId): F[Int]
