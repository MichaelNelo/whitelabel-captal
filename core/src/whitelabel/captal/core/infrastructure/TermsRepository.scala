package whitelabel.captal.core.infrastructure

import whitelabel.captal.core.terms.TermsTranslation

/** A version of the terms of service. One series for every location: a change to the terms is a new
  * version, with a higher `number`; a translation added to or taken from one is not.
  */
final case class TermsVersion(id: String, number: Int)

trait TermsRepository[F[_]]:
  /** The version in force, the highest one. `None` before any exists. */
  def current: F[Option[TermsVersion]]

/** What a version of the terms says, apart from which one is in force: what the backoffice changes,
  * and the portal does not read through `core`.
  */
trait TermsTextRepository[F[_]]:
  /** Its text in each locale it is written in, by locale. */
  def translations(version: TermsVersion): F[List[TermsTranslation]]
