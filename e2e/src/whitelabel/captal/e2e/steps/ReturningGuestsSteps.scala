package whitelabel.captal.e2e.steps

import com.microsoft.playwright.options.AriaRole
import com.microsoft.playwright.{Locator, Page}
import io.cucumber.scala.{EN, ScalaDsl}
import whitelabel.captal.e2e.backoffice.{Backoffice, Locations}
import whitelabel.captal.e2e.guest.Portal
import whitelabel.captal.e2e.scenario.World
import whitelabel.captal.mcp.tools.SurveyTools

/** Identification across visits: what a guest who has been here before is asked, and how a new
  * phone that gives a known email is taken for the guest it belongs to. A location of its own has
  * one minute of access, so that a return visit can be made within a scenario, and one promo with
  * its survey, which every visit plays: an advertiser's video drops out of the draw once its survey
  * is answered, and a return would then have nothing to play.
  */
final class ReturningGuestsSteps extends ScalaDsl with EN:
  private def button(page: Page, key: String) = page.getByRole(
    AriaRole.BUTTON,
    Page.GetByRoleOptions().setName(Locations.text(key)))

  /** What a question's submit button needs: one field, filled in. */
  private def answer(page: Page, value: String): Unit =
    page.locator("input").first().fill(value)
    button(page, "question.submit").click()

  private def watch(page: Page): Unit = page
    .locator(".video-progress-btn.active")
    .click(Locator.ClickOptions().setTimeout(60_000))

  /** Polls the session's phase until it is `phase`, for `seconds` at most. */
  private def awaitPhase(who: Portal.Guest, phase: String, seconds: Int): Unit =
    val deadline = System.nanoTime + seconds * 1_000_000_000L
    while !who.phase.equalsIgnoreCase(phase) do
      if System.nanoTime > deadline then
        throw AssertionError(
          s"${who.name}'s session is ${who.phase}, not $phase after $seconds seconds")
      Thread.sleep(1000)

  /** The email a guest's phone answers with. Derived from the guest's own click id, so it is new
    * for every run and every guest, and a phone can give it again without storing it.
    */
  private def addressOf(who: Portal.Guest): String =
    s"${who.name}.${who.clickId.stripPrefix("e2e-").take(8)}@example.com"

  /** The texts of the identification questions of `category`, in any locale. */
  private def questionTexts(category: String): Set[String] =
    Backoffice
      .tool[SurveyTools.ListRequest, SurveyTools.ListResponse](
        "list_surveys",
        SurveyTools.ListRequest())
      .surveys
      .filter(survey => survey.active && survey.locationSlug.isEmpty && survey.category == category)
      .flatMap(_.questions.filter(_.active).flatMap(_.text.map(_.value)))
      .toSet

  /** A location of its own: one minute of access, one promo with its survey. */
  private def promoLocation(name: String): Locations.Deployed =
    Locations.once(s"returning-guests-$name"):
      val location = Locations.create(name, accessMinutes = 1)
      Locations.addPromo(location, "E2E promo", priority = 1, question = Some("Did you like it?"))
      Locations.deploy(location)

  /** A whole first visit: the welcome page, the email, the video, its survey, and access. */
  private def firstVisit(name: String): Unit =
    val who = World.arrive(name)
    button(who.page, "welcome.button.start").click()
    answer(who.page, addressOf(who))
    watch(who.page)
    answer(who.page, "Yes")
    awaitPhase(who, "authorized", 90)

  Given(
    """a deployed location {string} with a promo of the venue, whose guests get one minute of access""") {
    (name: String) =>
      World.use(name, promoLocation(name))
  }

  Given("""{word} has had a first visit""") { (name: String) =>
    firstVisit(name)
  }

  When("""{word} waits for the access to run out""") { (name: String) =>
    awaitPhase(World.guest(name), "welcome", 180)
  }

  When("""{word} answers the email question with the address of {word}""") {
    (name: String, other: String) =>
      answer(World.guest(name).page, addressOf(World.guest(other)))
  }

  Then("""{word} is asked the {word} question""") { (name: String, category: String) =>
    val who = World.guest(name)
    val shown = who.page.locator(".question-title").first()
    shown.waitFor(Locator.WaitForOptions().setTimeout(30_000))
    val text = shown.textContent().trim
    val expected = questionTexts(category)
    if !expected.contains(text) then
      throw AssertionError(
        s"$name was asked \"$text\", not one of the $category questions: ${expected.mkString(
            ", ")}")
  }

  Then("""{word} is shown the video straight away""") { (name: String) =>
    awaitPhase(World.guest(name), "advertiser_video", 30)
  }
end ReturningGuestsSteps
