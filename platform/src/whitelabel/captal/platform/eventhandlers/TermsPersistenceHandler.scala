package whitelabel.captal.platform.eventhandlers

import io.getquill.*
import whitelabel.captal.core.application.Event
import whitelabel.captal.core.terms.Event as TermsEvent
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.{Terms, TermsRow, TermsTranslationRow}
import zio.*

/** The versions of the terms of service and their texts, as the backoffice's commands decided them.
  * A device's acceptance is the guest's, written by [[UserPersistenceHandler]].
  */
object TermsPersistenceHandler:
  def apply(): DbEventHandler[Event] =
    new DbEventHandler[Event]:
      def handle(events: List[Event], quill: QuillSqlite): Task[Unit] =
        import quill.*
        ZIO.foreachDiscard(events):
          case Event.Terms(TermsEvent.VersionPublished(id, version, translations, occurredAt)) =>
            val at = occurredAt.toString
            val texts = translations.map(t => TermsTranslationRow(id, t.locale, t.text, at))
            run(query[TermsRow].insertValue(lift(TermsRow(id, version, at)))) *>
              run(liftQuery(texts).foreach(text => query[TermsTranslationRow].insertValue(text)))
                .unit
          case Event.Terms(TermsEvent.TranslationSet(terms, translation, occurredAt)) =>
            Terms
              .saveTranslation(
                quill,
                TermsTranslationRow(
                  terms,
                  translation.locale,
                  translation.text,
                  occurredAt.toString))
              .unit
          case Event.Terms(TermsEvent.TranslationRemoved(terms, locale, _)) =>
            run(
              query[TermsTranslationRow]
                .filter(t => t.termsId == lift(terms) && t.locale == lift(locale))
                .delete).unit
          case _ =>
            ZIO.unit
      end handle
end TermsPersistenceHandler
