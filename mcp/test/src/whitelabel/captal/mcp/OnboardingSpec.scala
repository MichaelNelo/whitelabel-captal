package whitelabel.captal.mcp

import whitelabel.captal.core.application.queries.ReadinessStep
import whitelabel.captal.mcp.resources.Readiness
import whitelabel.captal.mcp.tools.Prompts
import whitelabel.captal.platform.{AccessPointRow, UnifiAccess}
import zio.test.*

/** How the readiness record reads to the operator: the onboarding prompt that renders it. Which
  * steps a location has left, and in what order, is `core`'s rule, tested there (`ReadinessTests`);
  * what is read is the e2e's, which provisions a location end to end.
  */
object OnboardingSpec extends ZIOSpecDefault:

  private def readiness(nextSteps: List[ReadinessStep] = Nil) = Readiness.LocationReadiness(
    slug = "spec",
    name = "Spec",
    active = true,
    unifiConfigured = true,
    unifiEnabled = true,
    locales = List(Readiness.LocaleGap("es", 0)),
    requiredKeys = 39,
    hasStyles = true,
    hasIcon = true,
    activeVideos = 1,
    pendingVideos = 0,
    advertiserSurveys = 1,
    adVideos = 1,
    strandedAdVideos = 0,
    advertisers = 1,
    missingIdentificationSurveys = Nil,
    termsVersion = Some(1),
    localesWithoutTerms = Nil,
    nextSteps = nextSteps.map(Readiness.render)
  )

  def spec =
    suite("Onboarding")(
      test("the prompt says where you stopped and that stopping is fine"):
        val rendered = Prompts.render(
          readiness(List(ReadinessStep.NoTranslations, ReadinessStep.NoVideos)))
        assertTrue(
          rendered.contains("Onboarding 'spec'"),
          rendered.contains("1. No translations at all"),
          rendered.contains("no commit step and no rollback"),
          // and it must not claim to deploy, but must name what does
          rendered.contains("Nothing here deploys"),
          rendered.contains("deploy_location"),
          // the CLI this used to name was deleted; a prompt is read by a model, which will try it
          !rendered.contains("captal locations")
        )
      ,
      test("a finished location renders as finished rather than as an empty list"):
        val rendered = Prompts.render(readiness())
        assertTrue(rendered.contains("Nothing here blocks a deploy."))
      ,
      test("each step names the tool that takes it"):
        assertTrue(
          Readiness.render(ReadinessStep.NoAccessPoint).contains("configure_unifi"),
          Readiness
            .render(ReadinessStep.IdentificationSurveysMissing(List("email", "profiling")))
            .contains("email and profiling"),
          Readiness.render(ReadinessStep.LocaleIncomplete("en", 2, 39)).contains("2 of 39")
        )
      ,
      test("an access point's controller takes its own duration, and defaults its port"):
        val point = AccessPointRow(
          id = "ap-1",
          locationId = "l-1",
          mac = "1c:6a:1b:ee:5c:be",
          unifiHost = "10.0.0.1",
          unifiPort = None,
          unifiSiteId = "site-uuid",
          unifiApiToken = "tok",
          createdAt = "",
          updatedAt = ""
        )
        val defaults = UnifiAccess.of(point)
        val set = UnifiAccess.of(point.copy(unifiPort = Some(8443), durationMinutes = Some(30)))
        assertTrue(
          defaults == UnifiAccess("10.0.0.1", "tok", 443, "site-uuid", 1440),
          set.port == 8443,
          set.defaultDurationMinutes == 30)
    )
end OnboardingSpec
