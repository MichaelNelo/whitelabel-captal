package whitelabel.captal.e2e.steps

import io.cucumber.scala.{EN, ScalaDsl}
import whitelabel.captal.e2e.backoffice.Locations
import whitelabel.captal.e2e.guest.Portal
import whitelabel.captal.e2e.scenario.World

/** The terms of service a guest accepts before the flow begins: the dialog starting opens, and the
  * operator publishing a new version.
  */
final class TermsSteps extends ScalaDsl with EN:

  When("""{word} accepts the terms of service""") { (name: String) =>
    val page = World.guest(name).page
    assert(Portal.askedForTerms(page), s"$name was not asked to accept the terms of service")
    Portal.acceptTerms(page)
  }

  When("""{word} declines the terms of service""") { (name: String) =>
    val page = World.guest(name).page
    assert(Portal.askedForTerms(page), s"$name was not asked to accept the terms of service")
    Portal.declineTerms(page)
  }

  Then("""{word} is told the terms of service have to be accepted""") { (name: String) =>
    val page = World.guest(name).page
    page.locator(".terms-required").waitFor()
    assert(page.locator(".terms-dialog").count() == 0, "The terms' dialog is still open")
    assert(page.locator(".welcome-button").isVisible, s"$name is no longer at the welcome")
  }

  Then("""{word} is asked to accept the terms of service""") { (name: String) =>
    assert(
      Portal.askedForTerms(World.guest(name).page),
      s"$name was not asked to accept the terms of service")
  }

  Then("""{word} is not asked to accept the terms of service""") { (name: String) =>
    assert(
      !Portal.askedForTerms(World.guest(name).page),
      s"$name was asked to accept the terms of service again")
  }

  When("""the operator publishes a new version of the terms of service""") { () =>
    val _ = Locations.publishTerms()
  }
end TermsSteps
