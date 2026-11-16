package whitelabel.captal.core.advertiser

import java.time.Instant
import java.util.UUID

import cats.Id
import cats.syntax.foldable.*
import utest.*
import whitelabel.captal.core.Op
import whitelabel.captal.core.application.Error as AppError
import whitelabel.captal.core.application.Event as AppEvent
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.infrastructure.AdvertiserRepository
import whitelabel.captal.core.survey.AdvertiserId

object AdvertiserTests extends TestSuite:
  private val Now: Instant = Instant.EPOCH
  private val Acme = Advertiser(AdvertiserId(UUID(0, 1)), "Acme", 10, active = true)
  private val Beta = Advertiser(AdvertiserId(UUID(0, 2)), "Beta", 5, active = true)
  private val NewId = AdvertiserId(UUID(0, 3))

  private val stored =
    new AdvertiserRepository[Id]:
      private val all = List(Acme, Beta)
      def find(id: AdvertiserId) = all.find(_.id == id)
      def named(name: String) = all.filter(_.name == name)

  /** The events and the result, or the advertiser errors refused with. */
  private def outcome[A](op: Op[AppEvent, AppError, A]): Either[List[Error], (List[AppEvent], A)] =
    Op.run(op)
      .left
      .map(
        _.toList
          .flatMap:
            case AppError.Advertiser(found) =>
              found.toList
            case _ =>
              Nil)

  val tests = Tests:
    test("CreateAdvertiser"):
      test("creates it active, its name trimmed"):
        val created = Advertiser(NewId, "Gamma", 3, active = true)
        assert(
          outcome(
            CreateAdvertiserHandler[Id](stored).handle(
              CreateAdvertiserCommand(NewId, " Gamma ", 3, Now))) ==
            Right((List(AppEvent.Advertiser(Event.AdvertiserCreated(created, Now))), created)))

      test("refuses a taken name, a negative priority and a blank name"):
        val creating = CreateAdvertiserHandler[Id](stored)
        assert(
          outcome(creating.handle(CreateAdvertiserCommand(NewId, "Acme", -1, Now))) ==
            Left(List(Error.NegativePriority(-1), Error.NameTaken("Acme", Acme.id))),
          outcome(creating.handle(CreateAdvertiserCommand(NewId, "  ", 1, Now))) ==
            Left(List(Error.BlankName))
        )

    test("UpdateAdvertiser"):
      test("keeps what it does not name"):
        val updated = outcome(
          UpdateAdvertiserHandler[Id](stored).handle(
            UpdateAdvertiserCommand(Acme.id, None, Some(3), Now)))
        assert(
          updated ==
            Right(
              (
                List(AppEvent.Advertiser(Event.AdvertiserUpdated(Acme.id, "Acme", 3, Now))),
                Acme.copy(priority = 3))))

      test("refuses a rename onto another's name, a change of nothing, and an unknown id"):
        val updating = UpdateAdvertiserHandler[Id](stored)
        assert(
          outcome(updating.handle(UpdateAdvertiserCommand(Acme.id, Some("Beta"), None, Now))) ==
            Left(List(Error.NameTaken("Beta", Beta.id))),
          outcome(updating.handle(UpdateAdvertiserCommand(Acme.id, None, None, Now))) ==
            Left(List(Error.NothingToUpdate)),
          outcome(updating.handle(UpdateAdvertiserCommand(NewId, None, Some(1), Now))) ==
            Left(List(Error.NotFound(NewId.asString)))
        )

    test("Retire and reinstate"):
      assert(
        outcome(
          RetireAdvertiserHandler[Id](stored).handle(RetireAdvertiserCommand(Acme.id, Now))) ==
          Right((List(AppEvent.Advertiser(Event.AdvertiserRetired(Acme.id, Now))), ())),
        outcome(
          ReinstateAdvertiserHandler[Id](stored).handle(
            ReinstateAdvertiserCommand(Acme.id, Now))) ==
          Right((List(AppEvent.Advertiser(Event.AdvertiserReinstated(Acme.id, Now))), ()))
      )
end AdvertiserTests
