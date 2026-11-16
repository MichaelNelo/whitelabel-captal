package whitelabel.captal.core.i18n

import java.time.Instant

import whitelabel.captal.core.location.LocationId

/** What the portal shows in place of `key`. */
final case class TranslationEntry(key: String, value: String)

enum Event:
  /** The location's portal reads `entries` in `locale` from now on, each in place of what that key
    * had; the keys left out keep theirs.
    */
  case TranslationsSet(
      location: LocationId,
      locale: String,
      entries: List[TranslationEntry],
      occurredAt: Instant)

  /** The location's portal no longer has a text for `key` in `locale`, and shows the key's name. */
  case TranslationRemoved(location: LocationId, locale: String, key: String, occurredAt: Instant)
end Event

enum Error:
  /** A change with nothing to write. */
  case NoEntries

  /** Keys the portal never reads: written, each would be dead weight, and a typo of a key it does
    * read would leave that one missing.
    */
  case UnknownKeys(keys: List[String])

/** How far one locale covers the keys the portal reads. */
final case class LocaleCoverage(
    locale: String,
    present: Int,
    missing: List[String],
    unknown: List[String])

object Coverage:
  /** Every key the portal reads, derived from [[I18n]]: a locale missing one shows its name. */
  def required: List[String] = I18nSchema.keys[I18n]

  /** The required keys `present` has no text for. */
  def missing(present: Map[String, String]): List[String] = I18nSchema.missing[I18n](present)

  /** Each locale of `texts`, by locale, with what it has, lacks and has that nobody reads. */
  def of(texts: Map[String, Map[String, String]]): List[LocaleCoverage] =
    val known = required.toSet
    texts
      .toList
      .sortBy(_._1)
      .map: (locale, present) =>
        LocaleCoverage(
          locale = locale,
          present = present.size,
          missing = missing(present),
          unknown = present.keys.filterNot(known.contains).toList.sorted)
end Coverage
