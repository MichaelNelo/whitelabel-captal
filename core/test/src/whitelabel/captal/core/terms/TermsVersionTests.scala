package whitelabel.captal.core.terms

import java.time.Instant

import cats.Id
import cats.syntax.foldable.*
import utest.*
import whitelabel.captal.core.Op
import whitelabel.captal.core.application.Error as AppError
import whitelabel.captal.core.application.Event as AppEvent
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.infrastructure.{TermsRepository, TermsTextRepository, TermsVersion}

object TermsVersionTests extends TestSuite:
  private val Now: Instant = Instant.EPOCH
  private val InForce = TermsVersion("terms-2", 2)
  private val Es = TermsTranslation("es", "Términos")
  private val En = TermsTranslation("en", "Terms")

  private def terms(inForce: Option[TermsVersion]) =
    new TermsRepository[Id]:
      def current = inForce

  private def texts(written: List[TermsTranslation]) =
    new TermsTextRepository[Id]:
      def translations(version: TermsVersion) = written

  /** The events emitted, or the terms errors refused with. */
  private def outcome[A](op: Op[AppEvent, AppError, A]): Either[List[Error], List[AppEvent]] =
    Op.run(op) match
      case Right((events, _)) =>
        Right(events)
      case Left(errors) =>
        Left(
          errors
            .toList
            .flatMap:
              case AppError.Terms(found) =>
                found.toList
              case _ =>
                Nil)

  val tests = Tests:
    test("PublishTermsVersion"):
      test("publishes the first version as 1, its locales trimmed"):
        val published = PublishTermsVersionHandler[Id](terms(None), texts(Nil)).handle(
          PublishTermsVersionCommand("terms-1", List(Es.copy(locale = " es ")), Now))
        assert(
          outcome(published) ==
            Right(List(AppEvent.Terms(Event.VersionPublished("terms-1", 1, List(Es), Now)))))

      test("publishes the one after the version in force"):
        val published = PublishTermsVersionHandler[Id](terms(Some(InForce)), texts(List(Es)))
          .handle(PublishTermsVersionCommand("terms-3", List(Es, En), Now))
        assert(
          outcome(published) ==
            Right(List(AppEvent.Terms(Event.VersionPublished("terms-3", 3, List(Es, En), Now)))))

      test("refuses a version that leaves out a locale of the one in force, or repeats one"):
        val refused = PublishTermsVersionHandler[Id](terms(Some(InForce)), texts(List(Es, En)))
          .handle(PublishTermsVersionCommand("terms-3", List(Es, Es.copy(text = " ")), Now))
        assert(
          outcome(refused) ==
            Left(
              List(
                Error.RepeatedLocales(List("es")),
                Error.BlankText(List("es")),
                Error.DroppedLocales(List("en")))))

      test("refuses a version with no text"):
        val refused = PublishTermsVersionHandler[Id](terms(None), texts(Nil)).handle(
          PublishTermsVersionCommand("terms-1", Nil, Now))
        assert(outcome(refused) == Left(List(Error.NoTranslations)))

    test("SetTermsTranslation"):
      test("gives the version in force the text"):
        assert(
          outcome(
            SetTermsTranslationHandler[Id](terms(Some(InForce))).handle(
              SetTermsTranslationCommand(En, Now))) ==
            Right(List(AppEvent.Terms(Event.TranslationSet("terms-2", En, Now)))))

      test("refuses before any version exists"):
        assert(
          outcome(
            SetTermsTranslationHandler[Id](terms(None)).handle(
              SetTermsTranslationCommand(En, Now))) == Left(List(Error.NoTermsYet)))

    test("RemoveTermsTranslation"):
      test("takes the locale out"):
        assert(
          outcome(
            RemoveTermsTranslationHandler[Id](terms(Some(InForce)), texts(List(Es, En))).handle(
              RemoveTermsTranslationCommand(" en ", Now))) ==
            Right(List(AppEvent.Terms(Event.TranslationRemoved("terms-2", "en", Now)))))

      test("refuses a locale the version is not written in, and its last text"):
        val removing = RemoveTermsTranslationHandler[Id](terms(Some(InForce)), texts(List(Es)))
        assert(
          outcome(removing.handle(RemoveTermsTranslationCommand("pt", Now))) ==
            Left(List(Error.NoSuchTranslation("pt", List("es")))),
          outcome(removing.handle(RemoveTermsTranslationCommand("es", Now))) ==
            Left(List(Error.LastTranslation("es")))
        )
end TermsVersionTests
