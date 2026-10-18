package whitelabel.captal.api

import whitelabel.captal.api.suites.*
import zio.*
import zio.test.*

object E2ETests extends ZIOSpecDefault:
  private val clearAndSeedNoise =
    TestFixtures.clearAllData *> TestFixtures.seedNoiseData *> TestUnifiClient.disable *>
      TestUnifiClient.reset

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
        AuthorizedSuite.suite,
        SoftDeleteSuite.suite
      ) @@ TestAspect.before(clearAndSeedNoise.orDie),
      // `ProvisioningSuite` sat inside the block above, outside the `before` that clears the
      // database, because it asserted against what a provisioning run had just written. It went
      // with the engine.
      //
      // `MigrationLockSuite` is outside it for its own reason: it migrates, so clearing the
      // application data around it would prove nothing. That it lives in this spec rather than its
      // own `ZIOSpecDefault` is load-bearing — see the note on the suite.
      MigrationLockSuite.suite
    ) @@ TestAspect.sequential
  ).provideShared(TestLayers.testEnv, ZLayer.fromZIO(TestFixtures.migrate.unit))
end E2ETests
