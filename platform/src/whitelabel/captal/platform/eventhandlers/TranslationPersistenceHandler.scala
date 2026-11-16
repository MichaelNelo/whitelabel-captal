package whitelabel.captal.platform.eventhandlers

import java.util.UUID

import io.getquill.*
import whitelabel.captal.core.application.Event
import whitelabel.captal.core.i18n.Event as I18nEvent
import whitelabel.captal.platform.LocalizedTextRow
import whitelabel.captal.platform.repositories.TranslationRepositoryQuill
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import zio.*

/** A location's portal texts, as the backoffice's commands decided them: each written on its
  * natural key, `(entity_id, locale, location_id)`, and taken out by retiring it.
  */
object TranslationPersistenceHandler:
  def apply(): DbEventHandler[Event] =
    new DbEventHandler[Event]:
      def handle(events: List[Event], quill: QuillSqlite): Task[Unit] =
        import quill.*
        ZIO.foreachDiscard(events):
          case Event.I18n(I18nEvent.TranslationsSet(location, locale, entries, occurredAt)) =>
            val at = occurredAt.toString
            val locationId = Option(location.asString)
            // Found whether retired or not, so writing a text brings a retired one back rather
            // than inserting a second row the natural key's unique index refuses.
            ZIO.foreachDiscard(entries): entry =>
              run(
                query[LocalizedTextRow].filter(r =>
                  r.entityId == lift(entry.key) && r.locale == lift(locale) &&
                    r.locationId == lift(locationId))).flatMap:
                _.headOption match
                  case Some(row) =>
                    run(
                      query[LocalizedTextRow]
                        .filter(_.id == lift(row.id))
                        .update(
                          _.value     -> lift(entry.value),
                          _.updatedAt -> lift(at),
                          _.isActive  -> 1)).unit
                  case None =>
                    run(
                      query[LocalizedTextRow].insertValue(
                        lift(
                          LocalizedTextRow(
                            id = UUID.randomUUID.toString,
                            entityId = entry.key,
                            locale = locale,
                            value = entry.value,
                            category = TranslationRepositoryQuill.Category,
                            createdAt = at,
                            updatedAt = at,
                            locationId = locationId,
                            isActive = 1
                          )))).unit
          case Event.I18n(I18nEvent.TranslationRemoved(location, locale, key, occurredAt)) =>
            run(
              query[LocalizedTextRow]
                .filter(r =>
                  r.entityId == lift(key) && r.locale == lift(locale) &&
                    r.locationId == lift(Option(location.asString)) && r.isActive == 1)
                .update(_.isActive -> 0, _.updatedAt -> lift(occurredAt.toString))).unit
          case _ =>
            ZIO.unit
      end handle
end TranslationPersistenceHandler
