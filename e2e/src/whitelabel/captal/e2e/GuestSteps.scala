package whitelabel.captal.e2e

import com.microsoft.playwright.Page
import com.microsoft.playwright.options.AriaRole
import io.cucumber.scala.{EN, ScalaDsl, Scenario}

/** What an operator sets up and what a guest does, in the words of the features. One instance per
  * scenario, so what it holds is that scenario's.
  */
final class GuestSteps extends ScalaDsl with EN:
  private var location: Option[Locations.Deployed] = None
  private val guests = scala.collection.mutable.Map.empty[String, Portal.Guest]

  private def deployed = location.getOrElse(throw IllegalStateException("No location yet"))

  private def guest(name: String) = guests.getOrElse(
    name,
    throw IllegalStateException(s"No guest called $name in this scenario"))

  private def button(page: Page, key: String) = page.getByRole(
    AriaRole.BUTTON,
    Page.GetByRoleOptions().setName(Locations.text(key)))

  /** What a question's submit button needs: one field, filled in. */
  private def answer(page: Page, value: String): Unit =
    page.locator("input").first().fill(value)
    button(page, "question.submit").click()

  After { (_: Scenario) =>
    guests.values.foreach(_.close())
  }

  Given("""a deployed location {string} with an advertiser video and its survey""") {
    (name: String) =>
      location = Some(Locations.withAdvertiserVideo(name))
  }

  When("""{word} opens the portal on a new phone""") { (name: String) =>
    guests(name) = Portal.arrive(name, deployed)
  }

  When("""{word} starts""") { (name: String) =>
    button(guest(name).page, "welcome.button.start").click()
  }

  When("""{word} answers the email question with {string}""") { (name: String, email: String) =>
    answer(guest(name).page, email)
  }

  When("""{word} watches the advertiser video to the end""") { (name: String) =>
    // Enabled once the video has ended; it plays muted, by itself, after the intro.
    guest(name)
      .page
      .locator(".video-progress-btn.active")
      .click(com.microsoft.playwright.Locator.ClickOptions().setTimeout(60_000))
  }

  When("""{word} answers the video's survey with {string}""") { (name: String, value: String) =>
    answer(guest(name).page, value)
  }

  // With UniFi off `finish` grants nothing: the session stays `Ready`, on the page that asks for it.
  Then("""{word}'s journey ends where his/her/their access would be granted""") { (name: String) =>
    val who = guest(name)
    who
      .page
      .getByText(Locations.text("ready.title"))
      .waitFor(com.microsoft.playwright.Locator.WaitForOptions().setTimeout(30_000))
    if !who.phase.equalsIgnoreCase("ready") then
      throw AssertionError(s"$name's session is ${who.phase}, not ready for access")
  }
end GuestSteps
