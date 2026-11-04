package whitelabel.captal.platform

import io.getquill.*
import whitelabel.captal.core.user
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.core.given
import whitelabel.captal.platform.schema.given
import zio.*

/** The terms of service, as the portal and the backoffice read them: one series of versions for
  * every location, the highest in force, each with a text per locale.
  */
object Terms:

  /** The version in force: the highest. `None` before any exists. */
  def current(quill: QuillSqlite): Task[Option[TermsRow]] =
    import quill.*
    run(query[TermsRow].sortBy(_.version)(using Ord.desc).take(1)).map(_.headOption)

  def translations(quill: QuillSqlite, termsId: String): Task[List[TermsTranslationRow]] =
    import quill.*
    run(query[TermsTranslationRow].filter(_.termsId == lift(termsId)).sortBy(_.locale))

  /** The locales the version in force is written in, which are the ones the portal offers. */
  def locales(quill: QuillSqlite): Task[List[String]] = current(quill).flatMap:
    case None =>
      ZIO.succeed(Nil)
    case Some(terms) =>
      translations(quill, terms.id).map(_.map(_.locale))

  /** Writes a version's text in one locale, replacing the one it had. Answers the rows written. */
  def saveTranslation(quill: QuillSqlite, text: TermsTranslationRow): Task[Long] =
    import quill.*
    val termsId = text.termsId
    val locale = text.locale
    val body = text.body
    val at = text.updatedAt
    run(
      query[TermsTranslationRow].filter(t =>
        t.termsId == lift(termsId) && t.locale == lift(locale))).flatMap: stored =>
      if stored.isEmpty then
        run(query[TermsTranslationRow].insertValue(lift(text)))
      else
        run(
          query[TermsTranslationRow]
            .filter(t => t.termsId == lift(termsId) && t.locale == lift(locale))
            .update(_.body -> lift(body), _.updatedAt -> lift(at)))

  /** Records that a device accepted the version `termsId` at `at`. */
  def accept(quill: QuillSqlite, device: user.DeviceId, termsId: String, at: String): Task[Unit] =
    import quill.*
    run(
      query[DeviceRow]
        .filter(_.id == lift(device))
        .update(_.termsId -> Some(lift(termsId)), _.termsAcceptedAt -> Some(lift(at)))).unit
end Terms
