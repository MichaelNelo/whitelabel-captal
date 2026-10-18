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
            q1.get.advertiserId.map(_.asString).contains(fixture.advertiserId),
            answerResp.code.isSuccess,
            isStep, // one question answered → Step(Ready)
            dbState.sessions.exists(_.phase == Phase.Ready)
          )
        ,
        // The same cycle over a promo, which is the venue's own video and has no advertiser.
        //
        // This was broken in three independent places, each of which failed by returning a
        // successful response carrying no survey — so the guest watched the promo and went
        // straight to Ready, and nothing anywhere said a question had been skipped:
        //
        //   - `nextAdvertiserSurveyQuery` filtered on `category = 'advertiser'` as well as on the
        //     video, reading as though the video's type decided whether its survey was reachable.
        //   - `fetchAdvertiserQuestionDetails` bound `survey.advertiser_id` inside an Option
        //     comprehension, so no advertiser threw away the question with it.
        //   - `findAnsweringVideoSurvey` required `sessions.current_advertiser_id` to be set
        //     before it would rebuild the state needed to answer.
        //
        // Hence asserting the answer too, not just that the question was served: the second and
        // third failures are on different sides of the response.
        test("a promo's survey is served and answered, with no advertiser to attribute it to"):
          for
            _       <- TestFixtures.seedEmailSurvey
            fixture <- TestFixtures.seedAdvertiserWithVideoAndSurvey(
              questionCount = 1,
              promo = true,
              videoTitle = "House Promo")
            backend <- testBackend
            cookie  <- createSession(backend)
            _       <- getNextSurvey(backend, cookie)
            _       <- postEmailAnswer(backend, cookie, "promo-survey@example.com")
            // No advertiser exists, so the ad selection finds nothing and the promo is served.
            videoResp  <- getNextVideo(backend, cookie)
            _          <- postMarkVideoWatched(backend, cookie, 10, completed = true)
            surveyResp <- getNextAdvertiserSurvey(backend, cookie)
            question = parseNextAdvertiserSurvey(surveyResp.body)
            answerResp <- postAdvertiserAnswer(
              backend,
              cookie,
              fixture.optionIds.head.head.asString)
            dbState <- TestFixtures.queryDbState
          yield assertTrue(
            videoResp.code.isSuccess,
            surveyResp.code.isSuccess,
            // The question itself, not merely a 200. The old behaviour was a 200 with a NextStep.
            question.isDefined,
            question.map(_.question.id).contains(fixture.questionIds.head),
            // Empty rather than invented. A promo belongs to the venue, so there is nobody to
            // attribute the answer to, and the response says so instead of omitting the survey.
            question.exists(_.advertiserId.isEmpty),
            answerResp.code.isSuccess,
            // And the answer was stored. Without this the assertions above would pass against a
            // path that serves the question and then refuses it.
            dbState.answers.exists(_.questionId == fixture.questionIds.head),
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
          yield assertTrue(!surveyResp.code.isSuccess || surveyResp.body.contains("WrongPhase"))
      )
end AdvertiserVideoSurveySuite
