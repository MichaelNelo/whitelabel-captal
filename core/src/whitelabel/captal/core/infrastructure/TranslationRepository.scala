package whitelabel.captal.core.infrastructure

import whitelabel.captal.core.location.LocationId

trait TranslationRepository[F[_]]:
  /** The location's portal texts in `locale`, by key: the ones in use. */
  def of(location: LocationId, locale: String): F[Map[String, String]]
