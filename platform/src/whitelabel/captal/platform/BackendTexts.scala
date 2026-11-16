package whitelabel.captal.platform

import java.util.UUID

import io.getquill.*
import whitelabel.captal.core.survey.question.LocalizedText
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import zio.*

/** The content's own wording — a question's, an option's, a video's title — in `localized_texts`
  * under the `backend` category, keyed by the entity's id: a question's description and placeholder
  * by its id with `_desc` and `_placeholder`, a video's description with `_desc`.
  */
object BackendTexts:
  val Category = "backend"

  /** The entity's texts in use, by locale. */
  def of(quill: QuillSqlite, entityId: String): Task[List[LocalizedText]] =
    import quill.*
    run(
      query[LocalizedTextRow].filter(t =>
        t.entityId == lift(entityId) && t.category == lift(Category) && t.isActive == 1)).map(
      _.sortBy(_.locale).map(row => LocalizedText(row.value, row.locale)))

  /** Writes a new entity's texts. */
  def write(
      quill: QuillSqlite,
      entityId: String,
      texts: List[LocalizedText],
      at: String): Task[Unit] =
    import quill.*
    val rows = texts.map(text => row(entityId, text, at))
    // Guarded, as the other batches are: most entities have no description or placeholder.
    ZIO
      .when(rows.nonEmpty)(
        run(liftQuery(rows).foreach(r => query[LocalizedTextRow].insertValue(r))))
      .unit

  /** Writes an entity's texts in the locales given, each in place of the one it had, found whether
    * retired or not; the other locales are kept. The natural key's unique index refuses a second
    * row, and rqlite reports the refusal as no row written, so the existing one has to be found.
    */
  def replace(
      quill: QuillSqlite,
      entityId: String,
      texts: List[LocalizedText],
      at: String): Task[Unit] =
    import quill.*
    ZIO.foreachDiscard(texts): text =>
      run(
        query[LocalizedTextRow].filter(t =>
          t.entityId == lift(entityId) && t.locale == lift(text.locale) &&
            t.category == lift(Category))).flatMap:
        _.headOption match
          case Some(existing) =>
            run(
              query[LocalizedTextRow]
                .filter(_.id == lift(existing.id))
                .update(_.value -> lift(text.value), _.updatedAt -> lift(at), _.isActive -> 1)).unit
          case None =>
            run(query[LocalizedTextRow].insertValue(lift(row(entityId, text, at)))).unit

  private def row(entityId: String, text: LocalizedText, at: String) = LocalizedTextRow(
    id = UUID.randomUUID.toString,
    entityId = entityId,
    locale = text.locale,
    value = text.value,
    category = Category,
    createdAt = at,
    updatedAt = at,
    locationId = None,
    isActive = 1
  )
end BackendTexts
