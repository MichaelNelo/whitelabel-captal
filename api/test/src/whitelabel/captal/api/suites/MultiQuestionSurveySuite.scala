package whitelabel.captal.api.suites

import whitelabel.captal.api.TestHelpers.*
import whitelabel.captal.api.{TestFixtures, TestHelpers}
import whitelabel.captal.core.application.{IdentificationSurveyType, Phase}
import zio.test.*

object MultiQuestionSurveySuite:
  val suite: Spec[TestEnv, Throwable] =
    zio
      .test
      .suite("Multi-Question Survey")(
        test("after answering email, user transitions to advertiser video (1 question per visit)"):
          for
            _          <- TestFixtures.seedEmailSurvey
            _          <- TestFixtures.seedMultiQuestionProfilingSurvey
            backend    <- testBackend
            localeResp <- putSetLocale(backend, "es")
            cookie = extractSessionCookie(localeResp).get
            // First call returns email question
            emailNext <- getNextSurvey(backend, cookie)
            emailParsed = parseNextSurvey(emailNext.body)
            // Answer email - response should contain next step
            answerResp <- postEmailAnswer(backend, cookie, "multi@example.com")
            // Verify session phase in database
            dbState <- TestFixtures.queryDbState
          yield assertTrue(
            emailParsed.isDefined,
            emailParsed.get.surveyType == IdentificationSurveyType.Email,
            answerResp.code.isSuccess,
            answerResp.body.contains("\"type\":\"step\""),
            answerResp.body.contains("\"phase\":\"advertiser_video\""),
            dbState.sessions.exists(_.phase == Phase.AdvertiserVideo)
          ))
end MultiQuestionSurveySuite
