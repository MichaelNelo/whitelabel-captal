package whitelabel.captal.core.location

import java.time.Instant

import cats.Id
import cats.data.NonEmptyList
import cats.syntax.foldable.*
import utest.*
import whitelabel.captal.core.Op
import whitelabel.captal.core.application.Error as AppError
import whitelabel.captal.core.application.Event as AppEvent
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.device.Mac
import whitelabel.captal.core.infrastructure.{AccessPointRepository, LocationRepository}
import whitelabel.captal.core.operation.RunId

object LocationTests extends TestSuite:
  private val Now: Instant = Instant.EPOCH
  private val Here = Slug.unsafe("plaza")
  private val Elsewhere = Slug.unsafe("brasa")
  private val Ap = Mac.parse("1c:6a:1b:ee:5c:be").get
  private val OtherAp = Mac.parse("aa:aa:aa:aa:aa:aa").get
  private val Token = Secret.parse("token").get

  private def point(
      mac: Mac,
      host: String = "unifi.example",
      settings: Settings = Settings.Default) = AccessPoint(
    mac,
    Controller(host, Some(8443), "default", Token),
    settings)

  private def at[S <: State](state: S): Location[S] = Location(
    LocationId("plaza-id"),
    Here,
    "Plaza",
    fingerprintRequired = true,
    state)

  private def deployed(points: AccessPoint*): Location[State] = at(
    State.Deployed(RunId("run-1"), NonEmptyList.fromListUnsafe(points.toList)))

  private def locations(found: Option[Location[State]]): LocationRepository[Id] =
    new LocationRepository[Id]:
      def find(slug: Slug) = found.filter(_.slug == slug)
      def findDeployed(id: LocationId) = None

  private def accessPoints(
      points: List[AccessPoint] = Nil,
      holders: Map[Mac, List[Slug]] = Map.empty): AccessPointRepository[Id] =
    new AccessPointRepository[Id]:
      def of(location: LocationId) = points
      def deployedHolders(mac: Mac) = holders.getOrElse(mac, Nil)

  /** The events emitted, or the location errors refused with. */
  private def outcome[A](op: Op[AppEvent, AppError, A]): Either[List[Error], (List[Event], A)] =
    Op.run(op) match
      case Right((events, value)) =>
        Right(
          (
            events.collect { case AppEvent.Location(event) =>
              event
            },
            value))
      case Left(errors) =>
        Left(
          errors
            .toList
            .flatMap {
              case AppError.Location(found) =>
                found.toList
              case _ =>
                Nil
            })

  val tests = Tests:
    test("Slug"):
      test("takes lowercase kebab-case, trimmed"):
        assert(Slug.parse("  plaza-norte-2 ").map(_.asString) == Right("plaza-norte-2"))
      test("has no length of its own"):
        assert(Slug.parse("a" * 40).isRight)
      test("refuses capitals, underscores, doubled or edge hyphens, and blank"):
        List("Plaza", "plaza_norte", "plaza--norte", "-plaza", "plaza-", "", "  ").foreach: raw =>
          assert(Slug.parse(raw).isLeft)

    test("Secret never shows"):
      assert(Token.toString == "Secret(***)", s"$Token" == "Secret(***)")
      assert(point(Ap).toString.contains("***"), !point(Ap).toString.contains("token"))

    test("Controller collects every reason it is not one"):
      val refused = Controller.of(" ", Some(70000), "", Token)
      assert(
        refused.left.map(_.toList) ==
          Left(List(Error.BlankHost, Error.BlankSiteId, Error.InvalidPort(70000))))

    test("SettingsChange"):
      test("changes what is given and keeps the rest"):
        val change = SettingsChange(durationMinutes = Some(Some(30)), unifiEnabled = Some(false))
        assert(
          change.applyTo(Settings.Default) ==
            Right(Settings.Default.copy(durationMinutes = Some(30), unifiEnabled = false)))
      test("clears the redirect URL"):
        val set = Settings.Default.copy(redirectUrl = Some("https://example.com"))
        assert(
          SettingsChange(redirectUrl = Some(None)).applyTo(set).map(_.redirectUrl) == Right(None))
      test("refuses a duration under a minute and a URL that is not http"):
        val change = SettingsChange(
          durationMinutes = Some(Some(0)),
          redirectUrl = Some(Some("ftp://example.com")))
        assert(
          change.applyTo(Settings.Default).left.map(_.toList) ==
            Left(List(Error.InvalidDuration(0), Error.InvalidRedirectUrl("ftp://example.com"))))

    test("CreateLocation"):
      val cmd = CreateLocationCommand(LocationId("new-id"), Here, "  Plaza ", Now)
      test("creates it"):
        val result = outcome(CreateLocationHandler(locations(None)).handle(cmd))
        assert(
          result.map(_._1) ==
            Right(List(Event.LocationCreated(LocationId("new-id"), Here, "Plaza", Now))))
        assert(result.map(_._2.state) == Right(State.Created))
      test("refuses a slug another location has"):
        val result = outcome(CreateLocationHandler(locations(Some(at(State.Created)))).handle(cmd))
        assert(result == Left(List(Error.SlugTaken(Here, "Plaza"))))
      test("refuses a blank name"):
        val result = outcome(CreateLocationHandler(locations(None)).handle(cmd.copy(name = " ")))
        assert(result == Left(List(Error.BlankName)))
      test("refuses a location that does not exist to the other commands"):
        val result = outcome(
          RemoveAccessPointHandler(locations(None), accessPoints()).handle(
            RemoveAccessPointCommand(Here, Ap, Now)))
        assert(result == Left(List(Error.NotFound(Here))))

    test("RequireFingerprint changes the location's requirement, in whatever state it is"):
      val result = outcome(
        RequireFingerprintHandler(locations(Some(at(State.Paused(RunId("run-2")))))).handle(
          RequireFingerprintCommand(Here, required = false, Now)))
      assert(
        result.map(_._1) ==
          Right(List(Event.FingerprintRequirementChanged(LocationId("plaza-id"), false, Now))))

    test("RegisterAccessPoint"):
      def register(
          token: Option[Secret] = Some(Token),
          settings: SettingsChange = SettingsChange()) = RegisterAccessPointCommand(
        Here,
        Ap,
        "unifi.example",
        Some(8443),
        "default",
        token,
        settings,
        Now)
      test("registers a new one with the default settings and what is given"):
        val result = outcome(
          RegisterAccessPointHandler(locations(Some(at(State.Created))), accessPoints()).handle(
            register(settings = SettingsChange(durationMinutes = Some(Some(90))))))
        val expected = point(Ap, settings = Settings.Default.copy(durationMinutes = Some(90)))
        assert(
          result.map(_._1) ==
            Right(List(Event.AccessPointRegistered(LocationId("plaza-id"), expected, Now))))
      test("changes the one it has, keeping its token and its settings when none are given"):
        val kept = Settings.Default.copy(redirectUrl = Some("https://example.com"))
        val result = outcome(
          RegisterAccessPointHandler(
            locations(Some(at(State.Created))),
            accessPoints(points = List(point(Ap, host = "old.example", settings = kept)))).handle(
            register(None)))
        assert(
          result.map(_._1) ==
            Right(
              List(
                Event.AccessPointUpdated(
                  LocationId("plaza-id"),
                  Ap,
                  point(Ap, settings = kept),
                  Now))))
      test("asks for a token for a new one"):
        val result = outcome(
          RegisterAccessPointHandler(locations(Some(at(State.Created))), accessPoints()).handle(
            register(None)))
        assert(result == Left(List(Error.TokenRequired(Ap))))
      test("refuses settings the portal could not use"):
        val result = outcome(
          RegisterAccessPointHandler(locations(Some(at(State.Created))), accessPoints()).handle(
            register(settings = SettingsChange(durationMinutes = Some(Some(0))))))
        assert(result == Left(List(Error.InvalidDuration(0))))
      test("refuses one another deployed location takes guests through"):
        val result = outcome(
          RegisterAccessPointHandler(
            locations(Some(at(State.Created))),
            accessPoints(holders = Map(Ap -> List(Elsewhere)))).handle(register()))
        assert(result == Left(List(Error.AccessPointHeld(Ap, Elsewhere))))
      test("does not count the location itself as another"):
        val result = outcome(
          RegisterAccessPointHandler(
            locations(Some(deployed(point(Ap)))),
            accessPoints(points = List(point(Ap)), holders = Map(Ap -> List(Here)))).handle(
            register()))
        assert(result.isRight)

    test("UpdateAccessPoint"):
      def update(
          newMac: Option[Mac] = None,
          host: Option[String] = None,
          settings: SettingsChange = SettingsChange()) = UpdateAccessPointCommand(
        Here,
        Ap,
        newMac,
        host,
        None,
        None,
        settings,
        Now)
      test("moves it to a new MAC, keeping its controller and its settings"):
        val result = outcome(
          UpdateAccessPointHandler(
            locations(Some(at(State.Created))),
            accessPoints(points = List(point(Ap)))).handle(update(newMac = Some(OtherAp))))
        assert(
          result.map(_._1) ==
            Right(List(Event.AccessPointUpdated(LocationId("plaza-id"), Ap, point(OtherAp), Now))))
      test("changes its settings"):
        val result = outcome(
          UpdateAccessPointHandler(
            locations(Some(at(State.Created))),
            accessPoints(points = List(point(Ap)))).handle(
            update(settings = SettingsChange(unifiEnabled = Some(false)))))
        assert(result.map(_._2.settings.unifiEnabled) == Right(false))
      test("refuses a MAC the location already has"):
        val result = outcome(
          UpdateAccessPointHandler(
            locations(Some(at(State.Created))),
            accessPoints(points = List(point(Ap), point(OtherAp)))).handle(
            update(newMac = Some(OtherAp))))
        assert(result == Left(List(Error.AccessPointExists(OtherAp))))
      test("refuses a MAC another deployed location takes guests through"):
        val result = outcome(
          UpdateAccessPointHandler(
            locations(Some(at(State.Created))),
            accessPoints(points = List(point(Ap)), holders = Map(OtherAp -> List(Elsewhere))))
            .handle(update(newMac = Some(OtherAp))))
        assert(result == Left(List(Error.AccessPointHeld(OtherAp, Elsewhere))))
      test("refuses a blank host"):
        val result = outcome(
          UpdateAccessPointHandler(
            locations(Some(at(State.Created))),
            accessPoints(points = List(point(Ap)))).handle(update(host = Some(" "))))
        assert(result == Left(List(Error.BlankHost)))
      test("refuses one the location does not have"):
        val result = outcome(
          UpdateAccessPointHandler(locations(Some(at(State.Created))), accessPoints()).handle(
            update(host = Some("new.example"))))
        assert(result == Left(List(Error.NoSuchAccessPoint(Ap))))

    test("RemoveAccessPoint"):
      val remove = RemoveAccessPointCommand(Here, Ap, Now)
      test("removes one"):
        val result = outcome(
          RemoveAccessPointHandler(
            locations(Some(deployed(point(Ap), point(OtherAp)))),
            accessPoints(points = List(point(Ap), point(OtherAp)))).handle(remove))
        assert(
          result.map(_._1) ==
            Right(List(Event.AccessPointRemoved(LocationId("plaza-id"), Ap, Now))))
      test("refuses a deployed location's last one"):
        val result = outcome(
          RemoveAccessPointHandler(
            locations(Some(deployed(point(Ap)))),
            accessPoints(points = List(point(Ap)))).handle(remove))
        assert(result == Left(List(Error.LastAccessPoint(Ap))))
      test("takes the last one of a location that is not deployed"):
        val result = outcome(
          RemoveAccessPointHandler(
            locations(Some(at(State.Paused(RunId("run-2"))))),
            accessPoints(points = List(point(Ap)))).handle(remove))
        assert(result.isRight)
      test("refuses one the location does not have"):
        val result = outcome(
          RemoveAccessPointHandler(locations(Some(at(State.Created))), accessPoints()).handle(
            remove))
        assert(result == Left(List(Error.NoSuchAccessPoint(Ap))))
end LocationTests
