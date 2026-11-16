package whitelabel.captal.core.application.commands

import java.time.Instant

import cats.Monad
import cats.data.NonEmptyChain
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import whitelabel.captal.core.Op as CoreOp
import whitelabel.captal.core.application.{Error, Event}
import whitelabel.captal.core.infrastructure.{TermsRepository, TermsTextRepository}
import whitelabel.captal.core.terms.{Error as TermsError, Event as TermsEvent, TermsTranslation}

/** Publishes version `id` of the terms of service, written in `translations`: in force at once for
  * every location.
  */
final case class PublishTermsVersionCommand(
    id: String,
    translations: List[TermsTranslation],
    occurredAt: Instant)

/** The new version's number, the one after the version in force, or 1. Refused without a text, with
  * a text that has no locale or nothing in it, with two texts in one locale, or without a text in a
  * locale the version in force is written in.
  */
object PublishTermsVersionHandler:
  def apply[F[_]: Monad](
      terms: TermsRepository[F],
      texts: TermsTextRepository[F]): Handler.Aux[F, PublishTermsVersionCommand, Int] =
    new Handler[F, PublishTermsVersionCommand]:
      type Result = Int

      def handle(cmd: PublishTermsVersionCommand) = terms
        .current
        .flatMap: current =>
          current
            .fold(Monad[F].pure(List.empty[TermsTranslation]))(texts.translations)
            .map: previous =>
              val translations = cmd.translations.map(t => t.copy(locale = t.locale.trim))
              val locales = translations.map(_.locale)
              val repeated = locales.diff(locales.distinct).distinct
              val blank = translations.filter(_.text.trim.isEmpty).map(_.locale)
              val dropped = previous.map(_.locale).filterNot(locales.contains)
              val version = current.fold(1)(_.number + 1)
              decided(
                List(
                  Option.when(translations.isEmpty)(TermsError.NoTranslations),
                  Option.when(locales.exists(_.isEmpty))(TermsError.BlankLocale),
                  Option.when(repeated.nonEmpty)(TermsError.RepeatedLocales(repeated)),
                  Option.when(blank.nonEmpty)(TermsError.BlankText(blank)),
                  Option.when(dropped.nonEmpty)(TermsError.DroppedLocales(dropped))
                ).flatten,
                TermsEvent.VersionPublished(cmd.id, version, translations, cmd.occurredAt),
                version
              )
end PublishTermsVersionHandler

/** Gives the version in force `translation`, added or in place of the one it had in that locale. */
final case class SetTermsTranslationCommand(translation: TermsTranslation, occurredAt: Instant)

/** Not a new version: nobody who accepted it is asked again. Refused before any version exists, and
  * for a text with no locale or nothing in it.
  */
object SetTermsTranslationHandler:
  def apply[F[_]: Monad](
      terms: TermsRepository[F]): Handler.Aux[F, SetTermsTranslationCommand, Unit] =
    new Handler[F, SetTermsTranslationCommand]:
      type Result = Unit

      def handle(cmd: SetTermsTranslationCommand) = terms
        .current
        .map:
          case None =>
            refusedTerms(TermsError.NoTermsYet)
          case Some(version) =>
            val translation = cmd.translation.copy(locale = cmd.translation.locale.trim)
            decided(
              List(
                Option.when(translation.locale.isEmpty)(TermsError.BlankLocale),
                Option.when(translation.text.trim.isEmpty)(
                  TermsError.BlankText(List(translation.locale)))
              ).flatten,
              TermsEvent.TranslationSet(version.id, translation, cmd.occurredAt),
              ()
            )
end SetTermsTranslationHandler

/** Takes `locale` out of the version in force. */
final case class RemoveTermsTranslationCommand(locale: String, occurredAt: Instant)

/** Not a new version. Refused before any version exists, for a locale the version is not written
  * in, and for its last text, without which no portal could show the terms.
  */
object RemoveTermsTranslationHandler:
  def apply[F[_]: Monad](
      terms: TermsRepository[F],
      texts: TermsTextRepository[F]): Handler.Aux[F, RemoveTermsTranslationCommand, Unit] =
    new Handler[F, RemoveTermsTranslationCommand]:
      type Result = Unit

      def handle(cmd: RemoveTermsTranslationCommand) = terms
        .current
        .flatMap:
          case None =>
            Monad[F].pure(refusedTerms(TermsError.NoTermsYet))
          case Some(version) =>
            texts
              .translations(version)
              .map: translations =>
                val locale = cmd.locale.trim
                val has = translations.map(_.locale)
                decided(
                  if !has.contains(locale) then
                    List(TermsError.NoSuchTranslation(locale, has))
                  else if has.sizeIs == 1 then
                    List(TermsError.LastTranslation(locale))
                  else
                    Nil
                  ,
                  TermsEvent.TranslationRemoved(version.id, locale, cmd.occurredAt),
                  ()
                )
end RemoveTermsTranslationHandler

/** Refused with `errors` when there are any; otherwise `event`, answering `result`. */
private def decided[A](errors: List[TermsError], event: => TermsEvent, result: => A): Op[A] =
  NonEmptyChain.fromSeq(errors) match
    case Some(found) =>
      CoreOp.fail[Event, Error, A](Error.Terms(found))
    case None =>
      CoreOp.emit[Event, Error, A](Event.Terms(event), result)

private def refusedTerms[A](error: TermsError): Op[A] = CoreOp.fail[Event, Error, A](
  Error.Terms(NonEmptyChain.one(error)))
