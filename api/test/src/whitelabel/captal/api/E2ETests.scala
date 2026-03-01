package whitelabel.captal.api

import whitelabel.captal.api.suites.*
import zio.*
import zio.test.*

object E2ETests extends ZIOSpecDefault:
  private val clearAndSeedNoise = TestFixtures.clearAllData *> TestFixtures.seedNoiseData

  def spec: Spec[Any, Throwable] = (
    suite("Identification Survey Flow")(
      LocaleSessionSuite.suite,
      SessionManagementSuite.suite,
      EmailSurveySuite.suite,
      SurveyProgressionSuite.suite,
      MultiQuestionSurveySuite.suite,
      ValidationSuite.suite,
      PhaseValidationSuite.suite
    ) @@ TestAspect.sequential @@ TestAspect.before(clearAndSeedNoise.orDie)
  ).provideShared(TestLayers.testEnv, ZLayer.fromZIO(TestFixtures.migrate.unit))
end E2ETests
