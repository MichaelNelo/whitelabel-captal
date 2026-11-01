package whitelabel.captal.e2e

import com.microsoft.playwright.{Locator, PlaywrightException}
import io.cucumber.scala.{EN, ScalaDsl}
import whitelabel.captal.mcp.tools.DeployTools

/** Pausing, resuming and destroying a location with the backoffice's tools, and what a guest sees
  * and gets while it is out and after it is back: the maintenance page, the journey again, the
  * session a guest was in, and a neighbouring location that keeps serving. Each location is the
  * feature's own, built once per run.
  */
final class PauseResumeSteps extends ScalaDsl with EN:

  /** The maintenance page's title, as `Maintenance.scala` renders it. */
  private val MaintenanceTitle = ".maintenance-title"

  /** A location of the feature's own: created, given its advertiser video and deployed. */
  private def own(name: String): Locations.Deployed =
    Locations.once(s"pause-resume-$name") {
      val location = Locations.create(name)
      Locations.addAdvertiserVideo(location)
      Locations.deploy(location)
    }

  /** `pause_location` or `resume_location` for `slug`, followed to the end of its run. */
  private def operate(tool: String, slug: String): DeployTools.RunView = Backoffice.awaitRun(
    Backoffice.tool[DeployTools.PauseRequest, DeployTools.RunView](
      tool,
      DeployTools.PauseRequest(slug)))

  Given("""a location {string} of its own, deployed with an advertiser video""") { (name: String) =>
    World.use(name, own(name))
  }

  When("""the operator pauses {string}""") { (name: String) =>
    val finished = operate("pause_location", World.location(name).slug)
    if finished.paused.isEmpty then
      throw AssertionError(s"The pause of $name ended without a pause result")
  }

  When("""the operator resumes {string}""") { (name: String) =>
    val location = World.location(name)
    operate("resume_location", location.slug)
    // The run can end before the load balancer has health-checked the task; the portal answers once it has.
    Portal.awaitServing(location)
  }

  When("""the operator destroys {string}""") { (name: String) =>
    val slug = World.location(name).slug
    val finished = Backoffice.awaitRun(
      Backoffice.tool[DeployTools.DestroyRequest, DeployTools.RunView](
        "destroy_location",
        DeployTools.DestroyRequest(slug = slug, confirm = slug)))
    if finished.destroyed.isEmpty then
      throw AssertionError(s"The destroy of $name ended without a destroy result")
  }

  /** A guest's next step once the location is paused. The portal may have left the question
    * already: any call it makes is answered `paused`, and sends the guest away. If it is still on
    * screen, the guest answers it, and that call is the one.
    */
  When("""{word} carries on with the email question, if her/his/their portal still shows it""") {
    (name: String) =>
      val page = World.guest(name).page
      val question = page.locator(".question-title")
      if question.count() > 0 && question.first().isVisible then
        page.locator("input").first().fill(s"guest-${java.util.UUID.randomUUID}@example.com")
        page
          .getByRole(
            com.microsoft.playwright.options.AriaRole.BUTTON,
            com
              .microsoft
              .playwright
              .Page
              .GetByRoleOptions()
              .setName(Locations.text("question.submit")))
          .click()
  }

  /** The maintenance page is what the guest's phone shows: the edge serves it at the location's own
    * address while paused, and a guest inside the portal is sent to it by the portal.
    */
  Then("""{word} is shown the maintenance page""") { (name: String) =>
    val page = World.guest(name).page
    try
      page.locator(MaintenanceTitle).first().waitFor(Locator.WaitForOptions().setTimeout(30_000))
    catch
      case _: PlaywrightException =>
        throw AssertionError(
          s"$name's page is ${page.url()}, not the maintenance page: it is titled \"${page
              .title()}\"")
  }

  Then("""{word}'s session is at the phase {string}""") { (name: String, phase: String) =>
    val found = World.guest(name).phase
    if found != phase then
      throw AssertionError(s"$name's session is at $found, expected $phase")
  }

  /** The location's API answers its health check with `ok` only while the location is served. */
  Then("""the API of {string} answers nothing for {word}""") { (at: String, name: String) =>
    val response = World.guest(name).context.request().get(World.location(at).api("health"))
    if response.ok && response.text.contains("ok") then
      throw AssertionError(
        s"The API of $at still answers ${response.status()} to $name's phone after its destroy")
  }
end PauseResumeSteps
