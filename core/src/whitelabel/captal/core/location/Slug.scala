package whitelabel.captal.core.location

/** What addresses a location everywhere it lands as is: its URL prefix, its prefix in the assets
  * bucket, its AWS resources' names. Lowercase kebab-case — letters, digits, and single hyphens
  * between them.
  *
  * How long it may be, and which slugs mean something every location shares, are the
  * infrastructure's to say, so the adapters that meet those limits check them.
  */
opaque type Slug = String

object Slug:
  private val Pattern = "[a-z0-9]+(-[a-z0-9]+)*".r

  /** `text` trimmed, when it has the shape; [[Error.InvalidSlug]] otherwise, blank included. */
  def parse(text: String): Either[Error, Slug] =
    val slug = text.trim
    Either.cond(Pattern.matches(slug), slug, Error.InvalidSlug(slug))

  def unsafe(text: String): Slug =
    require(Pattern.matches(text), s"not a slug: '$text'")
    text

  extension (slug: Slug)
    def asString: String = slug
end Slug
