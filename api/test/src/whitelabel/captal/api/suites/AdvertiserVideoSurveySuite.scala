package whitelabel.captal.api.suites

import whitelabel.captal.api.TestFixtures
import whitelabel.captal.api.TestHelpers.*
import whitelabel.captal.core.application.Phase
import zio.test.*

object AdvertiserVideoSurveySuite:
  val suite: Spec[TestEnv, Throwable] =
    zio
      .test
      .suite("Advertiser Video Survey Endpoints")(
        test("Full cycle: video → survey Q1 → answer → Ready"):
          for
            _       <- TestFixtures.seedEmailSurvey
            fixture <- TestFixtures.seedAdvertiserWithVideoAndSurvey(questionCount = 2)
            backend <- testBackend
            cookie  <- createSession(backend)
            // Complete identification
            _ <- getNextSurvey(backend, cookie)
            _ <- postEmailAnswer(backend, cookie, "adv-survey@example.com")
            // Get and watch video
            _ <- getNextVideo(backend, cookie)
            _ <- postMarkVideoWatched(backend, cookie, 15, completed = true)
            // Now in AdvertiserVideoSurvey phase - get first question
            surveyResp <- getNextAdvertiserSurvey(backend, cookie)
            q1 = parseNextAdvertiserSurvey(surveyResp.body)
            // Answer one question → go to Ready
            answerResp <- postAdvertiserAnswer(
              backend,
              cookie,
              fixture.optionIds.head.head.asString)
            isStep = parseAdvertiserSurveyStep(answerResp.body)
            dbState <- TestFixtures.queryDbState
          yield assertTrue(
            surveyResp.code.isSuccess,
            q1.isDefined,
            q1.get.advertiserId.asString == fixture.advertiserId,
            answerResp.code.isSuccess,
            isStep, // one question answered → Step(Ready)
            dbState.sessions.exists(_.phase == Phase.Ready)
          )
        ,
        test("Phase validation: advertiser survey endpoints reject wrong phases"):
          for
            _       <- TestFixtures.seedEmailSurvey
            backend <- testBackend
            cookie  <- createSession(backend)
            // Still in Welcome phase
            surveyResp <- getNextAdvertiserSurvey(backend, cookie)
          yield assertTrue(!surveyResp.code.isSuccess || surveyResp.body.contains("wrong_phase"))
      )
end AdvertiserVideoSurveySuite
