package whitelabel.captal.core.application.queries

import whitelabel.captal.core.location.Slug

/** The stylesheet of the location `slug`. */
final case class StylesOfQuery(slug: Slug)

/** The brand icon of the location `slug`. */
final case class IconOfQuery(slug: Slug)

/** A document the location published — its stylesheet, its icon — or none, when it shows the
  * bundle's default.
  */
final case class PublishedDocument(slug: Slug, content: Option[String])
