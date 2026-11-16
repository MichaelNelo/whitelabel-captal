package whitelabel.captal.core.location

import java.time.Instant

import cats.Id
import cats.syntax.foldable.*
import utest.*
import whitelabel.captal.core.Op
import whitelabel.captal.core.application.Error as AppError
import whitelabel.captal.core.application.Event as AppEvent
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.infrastructure.LocationRepository

object LookTests extends TestSuite:
  private val Now: Instant = Instant.EPOCH
  private val Here = Slug.unsafe("plaza")
  private val Plaza = Location(LocationId("plaza-id"), Here, "Plaza", true, State.Created)

  private val locations =
    new LocationRepository[Id]:
      def find(slug: Slug) = Option.when(slug == Here)(Plaza)
      def byId(id: LocationId) = Option.when(id == Plaza.id)(Plaza)
      def findDeployed(id: LocationId) = None

  /** The events emitted, or the location errors refused with. */
  private def outcome(op: Op[AppEvent, AppError, Unit]): Either[List[Error], List[AppEvent]] =
    Op.run(op) match
      case Right((events, _)) =>
        Right(events)
      case Left(errors) =>
        Left(
          errors
            .toList
            .flatMap:
              case AppError.Location(found) =>
                found.toList
              case _ =>
                Nil)

  val tests = Tests:
    test("ChangeStyles"):
      test("replaces the stylesheet"):
        val css = ":root { --color-primary: red; }"
        assert(
          outcome(ChangeStylesHandler[Id](locations).handle(ChangeStylesCommand(Here, css, Now))) ==
            Right(List(AppEvent.Location(Event.StylesChanged(Plaza.id, Here, css, Now)))))

      test("refuses a stylesheet with nothing in it, and a location that does not exist"):
        val changing = ChangeStylesHandler[Id](locations)
        assert(
          outcome(changing.handle(ChangeStylesCommand(Here, "  ", Now))) ==
            Left(List(Error.BlankStylesheet)),
          outcome(changing.handle(ChangeStylesCommand(Slug.unsafe("brasa"), "a{}", Now))) ==
            Left(List(Error.NotFound(Slug.unsafe("brasa"))))
        )

    test("ChangeIcon"):
      test("replaces the icon, and refuses one with nothing in it"):
        val changing = ChangeIconHandler[Id](locations)
        assert(
          outcome(changing.handle(ChangeIconCommand(Here, "<svg/>", Now))) ==
            Right(List(AppEvent.Location(Event.IconChanged(Plaza.id, Here, "<svg/>", Now)))),
          outcome(changing.handle(ChangeIconCommand(Here, "", Now))) == Left(List(Error.BlankIcon))
        )
end LookTests
