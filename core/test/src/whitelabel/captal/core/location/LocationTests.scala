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
import whitelabel.captal.core.infrastructure.{
  AccessPointRepository,
  ControllerRepository,
  LocationRepository
}
import whitelabel.captal.core.operation.RunId

object LocationTests extends TestSuite:
  private val Now: Instant = Instant.EPOCH
  private val Here = Slug.unsafe("plaza")
  private val Elsewhere = Slug.unsafe("brasa")
  private val Ap = Mac.parse("1c:6a:1b:ee:5c:be").get
  private val OtherAp = Mac.parse("aa:aa:aa:aa:aa:aa").get
  private val Token = Secret.parse("token").get

  private def controller(host: String = "unifi.example") = Controller(
    ControllerId(s"controller-$host"),
    host,
    Some(8443),
    "default",
    Token)

  private val Unifi = controller()

  private def point(
      mac: Mac,
      host: String = "unifi.example",
      settings: Settings = Settings.Default) = AccessPoint(mac, controller(host), settings)

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
      def byId(id: LocationId) = found.filter(_.id == id)
      def findDeployed(id: LocationId) = None

  /** The location's `points`; `taken`, the location each access point has been taken by; `known`,
    * the system's access points and the controllers they answer to.
    */
  private def accessPoints(
      points: List[AccessPoint] = Nil,
      taken: Map[Mac, Slug] = Map.empty,
      known: Map[Mac, Controller] = Map.empty): AccessPointRepository[Id] =
    new AccessPointRepository[Id]:
      def of(location: LocationId) = points
      def takenBy(mac: Mac) = taken.get(mac)
      def controllerOf(mac: Mac) = known
        .get(mac)
        .orElse(points.find(_.mac == mac).map(_.controller))

  /** The system's controllers, `all`, and how many access points answer to each, `usedBy`. */
  private def controllers(
      all: List[Controller] = List(Unifi),
      usedBy: Map[ControllerId, Int] = Map.empty): ControllerRepository[Id] =
    new ControllerRepository[Id]:
      def find(id: ControllerId) = all.find(_.id == id)
      def at(host: String, port: Option[Int], siteId: String) = all
        .find(_.address == (host, port.getOrElse(443), siteId))
        .map(_.id)
      def uses(id: ControllerId) = usedBy.getOrElse(id, 0)

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
      val refused = Controller.of(ControllerId("c"), " ", Some(70000), "", Token)
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
            Left(
              List(
                Error.InvalidDuration(0),
                Error.InvalidRedirectUrl("ftp://example.com", RedirectTarget.Problem.NotHttp))))

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
      val Fresh = ControllerChoice.New(
        ControllerId("fresh"),
        "fresh.example",
        None,
        "default",
        Token)
      def register(
          controller: Option[ControllerChoice] = Some(ControllerChoice.Existing(Unifi.id)),
          settings: SettingsChange = SettingsChange()) = RegisterAccessPointCommand(
        Here,
        Ap,
        controller,
        settings,
        Now)
      def handler(
          location: Location[State] = at(State.Created),
          points: AccessPointRepository[Id] = accessPoints()) = RegisterAccessPointHandler(
        locations(Some(location)),
        points,
        controllers())
      test("configures a new one with a controller the system has, and the default settings"):
        val result = outcome(
          handler().handle(register(settings = SettingsChange(durationMinutes = Some(Some(90))))))
        val expected = point(Ap, settings = Settings.Default.copy(durationMinutes = Some(90)))
        assert(
          result.map(_._1) ==
            Right(List(Event.AccessPointRegistered(LocationId("plaza-id"), expected, Now))))
      test("registers a new controller ahead of a new access point that answers to it"):
        val result = outcome(handler().handle(register(Some(Fresh))))
        val made = Controller(ControllerId("fresh"), "fresh.example", None, "default", Token)
        assert(
          result.map(_._1) ==
            Right(
              List(
                Event.ControllerRegistered(made, Now),
                Event.AccessPointRegistered(
                  LocationId("plaza-id"),
                  AccessPoint(Ap, made, Settings.Default),
                  Now))))
      test("configures one the system has with its own controller"):
        val result = outcome(
          handler(points = accessPoints(known = Map(Ap -> controller("other.example")))).handle(
            register(None)))
        assert(result.map(_._2.controller) == Right(controller("other.example")))
      test("refuses another controller for one the system has"):
        val result = outcome(
          handler(points = accessPoints(known = Map(Ap -> controller("other.example")))).handle(
            register()))
        assert(
          result ==
            Left(List(Error.AccessPointAnswersTo(Ap, ControllerId("controller-other.example")))))
      test("asks for a controller for a new one"):
        assert(
          outcome(handler().handle(register(None))) == Left(List(Error.ControllerRequired(Ap))))
      test("refuses a new controller at the address of one the system has"):
        val there = ControllerChoice.New(
          ControllerId("again"),
          "unifi.example",
          Some(8443),
          "default",
          Token)
        assert(
          outcome(handler().handle(register(Some(there)))) ==
            Left(List(Error.ControllerExists(Unifi.id))))
      test("refuses a controller the system does not have"):
        val missing = ControllerChoice.Existing(ControllerId("missing"))
        assert(
          outcome(handler().handle(register(Some(missing)))) ==
            Left(List(Error.NoSuchController(ControllerId("missing")))))
      test("changes the one it has, keeping its settings when none are given"):
        val kept = Settings.Default.copy(redirectUrl = Some("https://example.com"))
        val result = outcome(
          handler(points = accessPoints(points = List(point(Ap, settings = kept)))).handle(
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
      test("refuses settings the portal could not use"):
        val result = outcome(
          handler().handle(register(settings = SettingsChange(durationMinutes = Some(Some(0))))))
        assert(result == Left(List(Error.InvalidDuration(0))))
      test("configures a location that is not deployed with one another location has taken"):
        val result = outcome(
          handler(points = accessPoints(taken = Map(Ap -> Elsewhere))).handle(register()))
        assert(result.isRight)
      test("refuses a deployed location one another location has taken"):
        val result = outcome(
          handler(deployed(point(OtherAp)), accessPoints(taken = Map(Ap -> Elsewhere))).handle(
            register()))
        assert(result == Left(List(Error.AccessPointHeld(Ap, Elsewhere))))
      test("does not count the location itself as another"):
        val result = outcome(
          handler(
            deployed(point(Ap)),
            accessPoints(points = List(point(Ap)), taken = Map(Ap -> Here))).handle(register()))
        assert(result.isRight)

    test("UpdateAccessPoint"):
      def update(
          newMac: Option[Mac] = None,
          controller: Option[ControllerId] = None,
          settings: SettingsChange = SettingsChange()) = UpdateAccessPointCommand(
        Here,
        Ap,
        newMac,
        controller,
        settings,
        Now)
      def handler(
          location: Location[State] = at(State.Created),
          points: AccessPointRepository[Id]) = UpdateAccessPointHandler(
        locations(Some(location)),
        points,
        controllers(List(Unifi, controller("other.example"))))
      test(
        "configures the access point of a new MAC in its place, with its controller and settings"):
        val result = outcome(
          handler(points = accessPoints(points = List(point(Ap)))).handle(
            update(newMac = Some(OtherAp))))
        assert(
          result.map(_._1) ==
            Right(List(Event.AccessPointUpdated(LocationId("plaza-id"), Ap, point(OtherAp), Now))))
      test("takes the controller of an access point the system has at the new MAC"):
        val result = outcome(
          handler(points = accessPoints(
            points = List(point(Ap)),
            known = Map(OtherAp -> controller("other.example")))).handle(
            update(newMac = Some(OtherAp))))
        assert(result.map(_._2.controller) == Right(controller("other.example")))
      test("moves it to another controller"):
        val result = outcome(
          handler(points = accessPoints(points = List(point(Ap)))).handle(
            update(controller = Some(ControllerId("controller-other.example")))))
        assert(result.map(_._2.controller) == Right(controller("other.example")))
      test("refuses a controller the system does not have"):
        val result = outcome(
          handler(points = accessPoints(points = List(point(Ap)))).handle(
            update(controller = Some(ControllerId("missing")))))
        assert(result == Left(List(Error.NoSuchController(ControllerId("missing")))))
      test("changes its settings"):
        val result = outcome(
          handler(points = accessPoints(points = List(point(Ap)))).handle(
            update(settings = SettingsChange(unifiEnabled = Some(false)))))
        assert(result.map(_._2.settings.unifiEnabled) == Right(false))
      test("refuses a MAC the location already has"):
        val result = outcome(
          handler(points = accessPoints(points = List(point(Ap), point(OtherAp)))).handle(
            update(newMac = Some(OtherAp))))
        assert(result == Left(List(Error.AccessPointExists(OtherAp))))
      test("refuses a deployed location a MAC another location has taken"):
        val result = outcome(
          handler(
            deployed(point(Ap)),
            accessPoints(points = List(point(Ap)), taken = Map(OtherAp -> Elsewhere))).handle(
            update(newMac = Some(OtherAp))))
        assert(result == Left(List(Error.AccessPointHeld(OtherAp, Elsewhere))))
      test("refuses one the location does not have"):
        val result = outcome(handler(points = accessPoints()).handle(update()))
        assert(result == Left(List(Error.NoSuchAccessPoint(Ap))))

    test("UpdateController"):
      test("changes where it is, keeping its token"):
        val result = outcome(
          UpdateControllerHandler(controllers()).handle(
            UpdateControllerCommand(Unifi.id, Some("moved.example"), None, None, None, Now)))
        assert(
          result.map(_._1) ==
            Right(List(Event.ControllerChanged(Unifi.copy(host = "moved.example"), Now))))
      test("refuses the address of another controller"):
        val result = outcome(
          UpdateControllerHandler(controllers(List(Unifi, controller("other.example")))).handle(
            UpdateControllerCommand(Unifi.id, Some("other.example"), None, None, None, Now)))
        assert(
          result == Left(List(Error.ControllerExists(ControllerId("controller-other.example")))))

    test("RemoveController"):
      test("removes one no access point answers to"):
        val result = outcome(
          RemoveControllerHandler(controllers()).handle(RemoveControllerCommand(Unifi.id, Now)))
        assert(result.map(_._1) == Right(List(Event.ControllerRemoved(Unifi.id, Now))))
      test("refuses one access points answer to"):
        val result = outcome(
          RemoveControllerHandler(controllers(usedBy = Map(Unifi.id -> 2))).handle(
            RemoveControllerCommand(Unifi.id, Now)))
        assert(result == Left(List(Error.ControllerInUse(Unifi.id, 2))))

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
