package whitelabel.captal.e2e.steps

import com.microsoft.playwright.Page
import io.cucumber.scala.{EN, ScalaDsl}
import whitelabel.captal.e2e.backoffice.{Backoffice, Locations}
import whitelabel.captal.e2e.scenario.World
import whitelabel.captal.e2e.venue.Venue
import whitelabel.captal.mcp.tools.UnifiTools

/** Access as the venue's controller sees it: how long a phone is let in, when it is taken out again
  * and what that ends for the guest's session; and the locations whose UniFi is turned off or whose
  * controller cannot be reached, where the guest waits at the ready page. Their UniFi settings are
  * in place before they deploy, since a running portal reads its controller's address once, at
  * start; the switch is read on every event.
  */
final class AccessSteps extends ScalaDsl with EN:
  /** A location whose guests get one minute of access, so that its expiry comes within a minute. */
  private def expiring: Locations.Deployed =
    Locations.once("access-expiry"):
      val location = Locations.create("access-expiry", accessMinutes = 1)
      Locations.addAdvertiserVideo(location)
      Locations.deploy(location)

  private def quiet: Locations.Deployed =
    Locations.once("access-unifi-off"):
      val location = Locations.create("access-unifi-off")
      Backoffice.tool[UnifiTools.SetEnabledRequest, UnifiTools.StatusResponse](
        "set_unifi_enabled",
        UnifiTools.SetEnabledRequest(location.slug, enabled = false))
      Locations.addAdvertiserVideo(location)
      Locations.deploy(location)

  private def unreachable: Locations.Deployed =
    Locations.once("access-unreachable"):
      val location = Locations.create("access-unreachable")
      Locations.configureUnifi(location.slug, Venue.AccessMinutes, host = "nowhere.captal.local")
      Locations.addAdvertiserVideo(location)
      Locations.deploy(location)

  /** Waits until `condition` holds, polling every second, or fails with `what` after `seconds`. */
  private def within(seconds: Long, what: String)(condition: => Boolean): Unit =
    val deadline = System.nanoTime + seconds * 1_000_000_000L
    while !scala.util.Try(condition).getOrElse(false) do
      if System.nanoTime > deadline then
        throw AssertionError(s"$what, within $seconds seconds")
      Thread.sleep(1000)

  /** Waits until the controller has been asked for `action` about `mac`, polling every three
    * seconds, and answers that call.
    */
  private def awaitCall(mac: String, action: String, seconds: Long, what: String): Venue.Call =
    val deadline = System.nanoTime + seconds * 1_000_000_000L
    var found = Venue.callsFor(mac).find(_.action == action)
    while found.isEmpty do
      if System.nanoTime > deadline then
        throw AssertionError(s"$what, within $seconds seconds")
      Thread.sleep(3000)
      found = Venue.callsFor(mac).find(_.action == action)
    found.get

  /** The guest's session as the portal reports it, once it is `ready`: then held for `seconds`,
    * since the controller is asked, if at all, just after the finish answers, and a call that comes
    * late would be missed by a check made at once.
    */
  private def holdReady(name: String, seconds: Long): Unit =
    val who = World.guest(name)
    within(60, s"$name's session did not reach the ready page")(who.phase == "ready")
    Thread.sleep(seconds * 1000)
    val phase = who.phase
    if phase != "ready" then
      throw AssertionError(
        s"$name's session is $phase, not ready, $seconds seconds after the survey")

  Given("""a deployed location {string} whose guests get one minute of access""") { (name: String) =>
    World.use(name, expiring)
  }

  Given("""a deployed location {string} with UniFi turned off""") { (name: String) =>
    World.use(name, quiet)
  }

  Given("""a deployed location {string} whose controller cannot be reached""") { (name: String) =>
    World.use(name, unreachable)
  }

  Then("""the venue's controller has not yet been asked to take {word}'s phone out""") {
    (name: String) =>
      val who = World.guest(name)
      val revoked = Venue.callsFor(who.mac).filter(_.action == "UNAUTHORIZE_GUEST_ACCESS")
      if revoked.nonEmpty then
        throw AssertionError(s"$name's phone was taken out before its time was up: $revoked")
  }

  Then("""the venue's controller is asked to take {word}'s phone out once its time is up""") {
    (name: String) =>
      val who = World.guest(name)
      val call = awaitCall(
        who.mac,
        "UNAUTHORIZE_GUEST_ACCESS",
        seconds = 180,
        what = s"the controller was never asked to take $name's phone out")
      if call.apiKey != Venue.ApiKey then
        throw AssertionError(s"$name's phone was taken out as $call")
  }

  Then("""{word}'s session is back at the welcome page""") { (name: String) =>
    val who = World.guest(name)
    within(120, s"$name's session did not go back to the welcome page")(who.phase == "welcome")
  }

  Then("""{word}'s session stays at the ready page""") { (name: String) =>
    holdReady(name, seconds = 15)
  }

  Then("""the venue's controller was not asked anything about {word}'s phone""") { (name: String) =>
    val who = World.guest(name)
    val calls = Venue.callsFor(who.mac)
    if calls.nonEmpty then
      throw AssertionError(
        s"$name's phone ${who.mac} was the subject of ${calls
            .size} calls to the controller: $calls")
  }

  Then("""{word} sees the spinner of the ready page""") { (name: String) =>
    val page: Page = World.guest(name).page
    within(30, s"$name does not see the ready page's spinner")(
      page.locator(".ready-view .spinner").isVisible)
  }
end AccessSteps
