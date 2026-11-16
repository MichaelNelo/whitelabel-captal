package whitelabel.captal.core.application.queries

import whitelabel.captal.core.i18n.LocaleCoverage
import whitelabel.captal.core.location.Slug

/** How far the location `slug`'s portal texts cover the keys it reads. */
final case class TranslationCoverageQuery(slug: Slug)

/** How many keys the portal reads, and each locale the location has any text in: what it has,
  * lacks, and has that nobody reads.
  */
final case class TranslationCoverageReport(slug: Slug, required: Int, locales: List[LocaleCoverage])
