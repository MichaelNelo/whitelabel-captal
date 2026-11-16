package whitelabel.captal.core.application.commands

import java.time.Instant

import cats.Monad
import cats.data.NonEmptyChain
import cats.syntax.functor.*
import whitelabel.captal.core.Op as CoreOp
import whitelabel.captal.core.application.{Error, Event}
import whitelabel.captal.core.i18n.{
  Coverage,
  Error as I18nError,
  Event as I18nEvent,
  TranslationEntry
}
import whitelabel.captal.core.infrastructure.{LocationRepository, TranslationRepository}
import whitelabel.captal.core.location.Slug

/** Writes `entries` in `locale` for the location `slug`, each in place of what that key had. */
final case class SetTranslationsCommand(
    slug: Slug,
    locale: String,
    entries: List[TranslationEntry],
    occurredAt: Instant)

/** The required keys the locale still has no text for, after the change. Refused as a whole for a
  * key the portal never reads — written, a typo would be silently never shown — and with nothing to
  * write. A key given twice keeps its last text.
  */
object SetTranslationsHandler:
  def apply[F[_]: Monad](locations: LocationRepository[F], translations: TranslationRepository[F])
      : Handler.Aux[F, SetTranslationsCommand, List[String]] =
    new Handler[F, SetTranslationsCommand]:
      type Result = List[String]

      def handle(cmd: SetTranslationsCommand) =
        Decided.located(locations, cmd.slug): location =>
          translations
            .of(location.id, cmd.locale)
            .map: stored =>
              val known = Coverage.required.toSet
              val unknown = cmd.entries.map(_.key).filterNot(known.contains).distinct.sorted
              val entries = cmd.entries.reverse.distinctBy(_.key).reverse
              val refusals =
                List(
                  Option.when(unknown.nonEmpty)(I18nError.UnknownKeys(unknown)),
                  Option.when(cmd.entries.isEmpty)(I18nError.NoEntries)).flatten
              NonEmptyChain.fromSeq(refusals) match
                case Some(errors) =>
                  CoreOp.fail[Event, Error, List[String]](Error.I18n(errors))
                case None =>
                  CoreOp.emit[Event, Error, List[String]](
                    Event.I18n(
                      I18nEvent.TranslationsSet(location.id, cmd.locale, entries, cmd.occurredAt)),
                    Coverage.missing(stored ++ entries.map(e => e.key -> e.value)))
end SetTranslationsHandler

/** Takes `key`'s text in `locale` out of the location `slug`: the portal shows the key's name until
  * it is written again.
  */
final case class RemoveTranslationCommand(
    slug: Slug,
    locale: String,
    key: String,
    occurredAt: Instant)

/** Whether there was a text to take out, and the required keys the locale has none for after. */
final case class TranslationRemoval(removed: Boolean, stillMissing: List[String])

/** A key with no text in that locale changes nothing, and is answered so. */
object RemoveTranslationHandler:
  def apply[F[_]: Monad](locations: LocationRepository[F], translations: TranslationRepository[F])
      : Handler.Aux[F, RemoveTranslationCommand, TranslationRemoval] =
    new Handler[F, RemoveTranslationCommand]:
      type Result = TranslationRemoval

      def handle(cmd: RemoveTranslationCommand) =
        Decided.located(locations, cmd.slug): location =>
          translations
            .of(location.id, cmd.locale)
            .map: stored =>
              if stored.contains(cmd.key) then
                CoreOp.emit[Event, Error, TranslationRemoval](
                  Event.I18n(
                    I18nEvent.TranslationRemoved(location.id, cmd.locale, cmd.key, cmd.occurredAt)),
                  TranslationRemoval(removed = true, Coverage.missing(stored - cmd.key))
                )
              else
                CoreOp.emitAll[Event, Error, TranslationRemoval](
                  Nil,
                  TranslationRemoval(removed = false, Coverage.missing(stored)))
end RemoveTranslationHandler
