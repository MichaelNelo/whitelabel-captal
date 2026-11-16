package whitelabel.captal.core.application.queries

import whitelabel.captal.core.terms.TermsTranslation

/** The terms of service in force, the same for every location. */
final case class TermsInForceQuery()

/** A version: its number, when it was published, and its text in each locale. */
final case class TermsVersionReport(
    version: Int,
    createdAt: String,
    translations: List[TermsTranslation])

/** The version in force, none before the first is published, and the numbers of the ones before it,
  * newest first.
  */
final case class TermsReport(current: Option[TermsVersionReport], earlier: List[Int])
