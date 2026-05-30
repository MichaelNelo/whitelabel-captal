package whitelabel.captal.cli.migrations

/** Semantic version tuple. Used for ordering migrations chronologically and for binary searching
  * the pending-since boundary in [[Migrations.pendingSince]].
  *
  * String-based comparison ("2.0.0" vs "10.0.0") would fail lexicographically; modelling as 3 Ints
  * gives the right numeric Ordering.
  */
final case class SemVer(major: Int, minor: Int, patch: Int):
  override def toString: String = s"$major.$minor.$patch"

object SemVer:
  given Ordering[SemVer] = Ordering.by(v => (v.major, v.minor, v.patch))

  val zero: SemVer = SemVer(0, 0, 0)

  /** Parse "2.0.1" → SemVer(2,0,1). Returns Left with diagnostic on bad input. Trims whitespace. */
  def parse(raw: String): Either[String, SemVer] = raw.trim.split('.').toList match
    case List(a, b, c) =>
      for
        ma <- a.toIntOption.toRight(s"non-integer major: $a")
        mi <- b.toIntOption.toRight(s"non-integer minor: $b")
        pa <- c.toIntOption.toRight(s"non-integer patch: $c")
      yield SemVer(ma, mi, pa)
    case _ =>
      Left(s"expected MAJOR.MINOR.PATCH, got '$raw'")

  /** Lenient parse — falls back to [[zero]] on failure. Used when reading external strings (state
    * file values, registry inputs) where a malformed value should default to "ancient" rather than
    * abort the program.
    */
  def parseOrZero(raw: String): SemVer = parse(raw).getOrElse(zero)
end SemVer
