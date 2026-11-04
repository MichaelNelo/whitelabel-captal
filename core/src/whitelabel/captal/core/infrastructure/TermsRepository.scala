package whitelabel.captal.core.infrastructure

/** A version of the terms of service. One series for every location: a change to the terms is a new
  * version, with a higher `number`; a translation added to or taken from one is not.
  */
final case class TermsVersion(id: String, number: Int)

trait TermsRepository[F[_]]:
  /** The version in force, the highest one. `None` before any exists. */
  def current: F[Option[TermsVersion]]
