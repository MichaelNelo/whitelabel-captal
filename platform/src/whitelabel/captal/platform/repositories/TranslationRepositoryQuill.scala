package whitelabel.captal.platform.repositories

import io.getquill.*
import whitelabel.captal.core.infrastructure.TranslationRepository
import whitelabel.captal.core.location.LocationId
import whitelabel.captal.platform.LocalizedTextRow
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import zio.*

/** A location's portal texts as `localized_texts` holds them: the `frontend` ones in use. */
object TranslationRepositoryQuill:
  /** The category the portal's own texts are stored under, apart from the content's wording. */
  val Category = "frontend"

  def apply(quill: QuillSqlite): TranslationRepository[Task] =
    import quill.*
    new TranslationRepository[Task]:
      def of(location: LocationId, locale: String): Task[Map[String, String]] = run(
        query[LocalizedTextRow].filter(r =>
          r.locationId == lift(Option(location.asString)) && r.locale == lift(locale) &&
            r.category == lift(Category) && r.isActive == 1)).map(
        _.map(row => row.entityId -> row.value).toMap)
end TranslationRepositoryQuill
