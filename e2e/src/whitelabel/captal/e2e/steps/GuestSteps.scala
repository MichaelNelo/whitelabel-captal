package whitelabel.captal.e2e.steps

import com.microsoft.playwright.options.AriaRole
import com.microsoft.playwright.{Locator, Page}
import io.cucumber.scala.{EN, ScalaDsl}
import whitelabel.captal.e2e.backoffice.Locations
import whitelabel.captal.e2e.guest.Portal
import whitelabel.captal.e2e.scenario.World
import whitelabel.captal.e2e.venue.Venue

/** What a guest does on the portal, and the locations they do it at, in the words of the features.
  * What they act on is in [[World]], so every step class shares it.
  */
final class GuestSteps extends ScalaDsl with EN:
  private def button(page: Page, key: String) = page.getByRole(
    AriaRole.BUTTON,
    Page.GetByRoleOptions().setName(Locations.text(key)))

  /** What a question's submit button needs: one field, filled in. */
  private def answer(page: Page, value: String): Unit =
    page.locator("input").first().fill(value)
    button(page, "question.submit").click()

  Given("""a deployed location {string} with an advertiser video and its survey""") {
    (name: String) =>
      World.use(name, Locations.withAdvertiserVideo(name))
  }

  When("""{word} opens the portal on a new phone""") { (name: String) =>
    World.arrive(name)
  }

  When("""{word} opens the portal of {string} on a new phone""") { (name: String, at: String) =>
    World.arrive(name, World.location(at))
  }

  When("""{word} comes back to the portal on the same phone""") { (name: String) =>
    World.keep(World.guest(name).returnTo())
  }

  When("""{word} comes back to the portal of {string} on the same phone""") {
    (name: String, at: String) =>
      World.keep(World.guest(name).returnTo(World.location(at)))
  }

  When("""{word} starts""") { (name: String) =>
    Portal.start(World.guest(name).page)
  }

  When("""{word} answers the email question with {string}""") { (name: String, email: String) =>
    answer(World.guest(name).page, email)
  }

  When("""{word} answers the question shown with {string}""") { (name: String, value: String) =>
    answer(World.guest(name).page, value)
  }

  When("""{word} watches the advertiser video to the end""") { (name: String) =>
    // Enabled once the video has ended; it plays muted, by itself, after the intro.
    World
      .guest(name)
      .page
      .locator(".video-progress-btn.active")
      .click(Locator.ClickOptions().setTimeout(60_000))
  }

  When("""{word} answers the video's survey with {string}""") { (name: String, value: String) =>
    answer(World.guest(name).page, value)
  }

  Then("""{word} has internet access""") { (name: String) =>
    val who = World.guest(name)
    val deadline = System.nanoTime + 60_000_000_000L
    while !who.phase.equalsIgnoreCase("authorized") do
      if System.nanoTime > deadline then
        throw AssertionError(s"$name's session is ${who.phase}, a minute after finishing")
      Thread.sleep(1000)
  }

  Then("""the venue's controller let {word}'s phone in for {int} minutes""") {
    (name: String, minutes: Int) =>
      val who = World.guest(name)
      val calls = Venue.callsFor(who.mac)
      val authorized = calls.filter(_.action == "AUTHORIZE_GUEST_ACCESS")
      if authorized.size != 1 then
        throw AssertionError(
          s"$name's phone ${who.mac} was authorized ${authorized.size} times: $calls")
      val call = authorized.head
      if call.timeLimitMinutes != Some(minutes) || call.apiKey != Venue.ApiKey then
        throw AssertionError(s"$name's phone was authorized as $call")
  }
end GuestSteps
