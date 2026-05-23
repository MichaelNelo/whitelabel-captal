package whitelabel.captal.api

import whitelabel.captal.api.suites.*
import zio.*
import zio.test.*

object E2ETests extends ZIOSpecDefault:
  private val clearAndSeedNoise = TestFixtures.clearAllData *> TestFixtures.seedNoiseData

  def spec: Spec[Any, Throwable] = (
    suite("E2E")(
      suite("Identification Survey Flow")(
        LocaleSessionSuite.suite,
        SessionManagementSuite.suite,
        EmailSurveySuite.suite,
        SurveyProgressionSuite.suite,
        MultiQuestionSurveySuite.suite,
        ValidationSuite.suite,
        PhaseValidationSuite.suite,
        SessionIsolationSuite.suite,
        VideoSuite.suite,
        AdvertiserVideoSurveySuite.suite,
        FinishSuite.suite,
        AuthorizedSuite.suite
      ) @@ TestAspect.before(clearAndSeedNoise.orDie),
      ProvisioningSuite.suite
    ) @@ TestAspect.sequential
  ).provideShared(TestLayers.testEnv, ZLayer.fromZIO(TestFixtures.migrate.unit))
end E2ETests
