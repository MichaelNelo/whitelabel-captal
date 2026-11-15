package whitelabel.captal.core.infrastructure

import whitelabel.captal.core.device.Mac
import whitelabel.captal.core.location.{AccessPoint, Location, LocationId, Slug, State}

trait LocationRepository[F[_]]:
  /** The location with this slug, in whatever state it is. */
  def find(slug: Slug): F[Option[Location[State]]]

  /** The location with this id, if it is deployed. */
  def findDeployed(id: LocationId): F[Option[Location[State.Deployed]]]

trait AccessPointRepository[F[_]]:
  /** The location's access points, whatever its state. */
  def of(location: LocationId): F[List[AccessPoint]]

  /** The deployed locations that take their guests through the access point `mac`. */
  def deployedHolders(mac: Mac): F[List[Slug]]
