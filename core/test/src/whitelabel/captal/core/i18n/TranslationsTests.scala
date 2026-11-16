package whitelabel.captal.core.i18n

import java.time.Instant

import cats.Id
import cats.syntax.foldable.*
import utest.*
import whitelabel.captal.core.Op
import whitelabel.captal.core.application.Error as AppError
import whitelabel.captal.core.application.Event as AppEvent
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.infrastructure.{LocationRepository, TranslationRepository}
import whitelabel.captal.core.location.{Location, LocationId, Slug, State}

object TranslationsTests extends TestSuite:
  private val Now: Instant = Instant.EPOCH
  private val Here = Slug.unsafe("plaza")
  private val Id = LocationId("plaza-id")
  private val First = Coverage.required.head
  private val Second = Coverage.required(1)

  private val locations =
    new LocationRepository[Id]:
      private val plaza = Location(Id, Here, "Plaza", fingerprintRequired = true, State.Created)
      def find(slug: Slug) = Option.when(slug == Here)(plaza)
      def byId(id: LocationId) = Option.when(id == Id)(plaza)
      def findDeployed(id: LocationId) = None

  private def stored(texts: Map[String, String]) =
    new TranslationRepository[Id]:
      def of(location: LocationId, locale: String) = texts

  /** The events and the result, or the i18n errors refused with. */
  private def outcome[A](op: Op[AppEvent, AppError, A]): Either[List[Error], (List[AppEvent], A)] =
    Op.run(op)
      .left
      .map(
        _.toList
          .flatMap:
            case AppError.I18n(found) =>
              found.toList
            case _ =>
              Nil)

  val tests = Tests:
    test("SetTranslations"):
      test("writes the entries, a key given twice with its last text, and answers what is missing"):
        val set = SetTranslationsHandler[Id](locations, stored(Map.empty)).handle(
          SetTranslationsCommand(
            Here,
            "es",
            List(TranslationEntry(First, "uno"), TranslationEntry(First, "dos")),
            Now))
        val written = List(TranslationEntry(First, "dos"))
        assert(
          outcome(set) ==
            Right(
              (
                List(AppEvent.I18n(Event.TranslationsSet(Id, "es", written, Now))),
                Coverage.required.filterNot(_ == First))))

      test("refuses a key the portal never reads, and a change with nothing in it"):
        val setting = SetTranslationsHandler[Id](locations, stored(Map.empty))
        assert(
          outcome(
            setting.handle(
              SetTranslationsCommand(Here, "es", List(TranslationEntry("nope", "x")), Now))) ==
            Left(List(Error.UnknownKeys(List("nope")))),
          outcome(setting.handle(SetTranslationsCommand(Here, "es", Nil, Now))) ==
            Left(List(Error.NoEntries))
        )

    test("RemoveTranslation"):
      test("takes out a key the locale has a text for"):
        val removed = RemoveTranslationHandler[Id](
          locations,
          stored(Map(First -> "uno", Second -> "dos"))).handle(
          RemoveTranslationCommand(Here, "es", First, Now))
        assert(
          outcome(removed) ==
            Right(
              (
                List(AppEvent.I18n(Event.TranslationRemoved(Id, "es", First, Now))),
                TranslationRemoval(removed = true, Coverage.required.filterNot(_ == Second)))))

      test("changes nothing for a key the locale has no text for"):
        val removed = RemoveTranslationHandler[Id](locations, stored(Map.empty)).handle(
          RemoveTranslationCommand(Here, "es", First, Now))
        assert(
          outcome(removed) == Right((Nil, TranslationRemoval(removed = false, Coverage.required))))

    test("Coverage"):
      test("reports each locale's present, missing and unknown keys"):
        val coverage = Coverage.of(Map("es" -> Map(First -> "uno", "stale.key" -> "x")))
        assert(
          coverage ==
            List(
              LocaleCoverage(
                "es",
                present = 2,
                missing = Coverage.required.filterNot(_ == First),
                unknown = List("stale.key"))))
end TranslationsTests
