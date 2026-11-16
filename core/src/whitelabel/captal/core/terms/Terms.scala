package whitelabel.captal.core.terms

import java.time.Instant

/** The terms of service in one locale, as the dialog shows them. */
final case class TermsTranslation(locale: String, text: String)

enum Event:
  /** Version `version` of the terms is in force, written in `translations`: every device is asked
    * to accept it on its next welcome.
    */
  case VersionPublished(
      id: String,
      version: Int,
      translations: List[TermsTranslation],
      occurredAt: Instant)

  /** The version in force, `terms`, has `translation` now, added or in place of the one it had in
    * that locale. Not a new version: nobody is asked again.
    */
  case TranslationSet(terms: String, translation: TermsTranslation, occurredAt: Instant)

  /** The version in force, `terms`, is no longer written in `locale`. */
  case TranslationRemoved(terms: String, locale: String, occurredAt: Instant)
end Event

enum Error:
  /** No version exists to change: the first is published, not edited. */
  case NoTermsYet

  /** A new version with no text. */
  case NoTranslations

  /** A text with no locale. */
  case BlankLocale

  /** A text with nothing in it, in these locales. */
  case BlankText(locales: List[String])

  /** Locales a new version gives more than one text for. */
  case RepeatedLocales(locales: List[String])

  /** Locales the version in force is written in that the new one leaves out: a guest reading one of
    * them would have no terms to accept.
    */
  case DroppedLocales(locales: List[String])

  /** The version in force has no text in `locale`; it has `has`. */
  case NoSuchTranslation(locale: String, has: List[String])

  /** `locale` is the version in force's only text: without it, no portal could show the terms. */
  case LastTranslation(locale: String)
end Error
