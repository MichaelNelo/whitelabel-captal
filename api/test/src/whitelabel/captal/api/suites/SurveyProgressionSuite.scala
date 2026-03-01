package whitelabel.captal.api.suites

import whitelabel.captal.api.TestHelpers.*
import whitelabel.captal.api.{TestFixtures, TestHelpers}
import whitelabel.captal.core.application.{IdentificationSurveyType, Phase}
import whitelabel.captal.core.survey.question.*
import whitelabel.captal.core.user
import zio.test.*

object SurveyProgressionSuite:
  // Use a different sessionId for "previous session" answers
  private val previousSessionId = user.SessionId.generate

  val suite: Spec[TestEnv, Throwable] =
    zio
      .test
      .suite("Survey Progression")(
        test("user with email is offered profiling survey in new session"):
          for
            surveys  <- TestFixtures.seedAllIdentificationSurveys
            testUser <- TestFixtures.createUser("completed@example.com")
            // Answer email question in a PREVIOUS session (to exhaust email pool)
            _ <- TestFixtures.createAnswer(
              testUser.userId,
              previousSessionId,
              surveys.email.questionId,
              "completed@example.com")
            backend    <- testBackend
            localeResp <- putSetLocale(backend, "es")
            cookie = extractSessionCookie(localeResp).get
            _ <- TestFixtures.linkSessionToUser(user.SessionId.unsafe(cookie), testUser.userId)
            nextResp <- getNextSurvey(backend, cookie)
            parsed = parseNextSurvey(nextResp.body)
          yield assertTrue(
            nextResp.code.isSuccess,
            parsed.isDefined,
            parsed.get.surveyId == surveys.profiling.surveyId,
            parsed.get.surveyType == IdentificationSurveyType.Profiling,
            parsed.get.question.id == surveys.profiling.questionId,
            parsed.get.question.questionType.isInstanceOf[QuestionType.Radio]
          )
        ,
        test("user who exhausted profiling pool is offered location survey"):
          for
            surveys  <- TestFixtures.seedAllIdentificationSurveys
            testUser <- TestFixtures.createUser("profiling-done@example.com")
            // Answer all questions in PREVIOUS sessions
            _ <- TestFixtures.createAnswer(
              testUser.userId,
              previousSessionId,
              surveys.email.questionId,
              "profiling-done@example.com")
            _ <- TestFixtures.createAnswer(
              testUser.userId,
              previousSessionId,
              surveys.profiling.questionId,
              "some-answer")
            backend    <- testBackend
            localeResp <- putSetLocale(backend, "es")
            cookie = extractSessionCookie(localeResp).get
            _ <- TestFixtures.linkSessionToUser(user.SessionId.unsafe(cookie), testUser.userId)
            nextResp <- getNextSurvey(backend, cookie)
            parsed = parseNextSurvey(nextResp.body)
          yield assertTrue(
            nextResp.code.isSuccess,
            parsed.isDefined,
            parsed.get.surveyId == surveys.location.surveyId,
            parsed.get.surveyType == IdentificationSurveyType.Location,
            parsed.get.question.id == surveys.location.questionId,
            parsed.get.question.questionType.isInstanceOf[QuestionType.Select]
          )
        ,
        test("user who exhausted all pools receives NextStep"):
          for
            surveys  <- TestFixtures.seedAllIdentificationSurveys
            testUser <- TestFixtures.createUser("all-done@example.com")
            // Answer ALL questions in PREVIOUS sessions
            _ <- TestFixtures.createAnswer(
              testUser.userId,
              previousSessionId,
              surveys.email.questionId,
              "all-done@example.com")
            _ <- TestFixtures.createAnswer(
              testUser.userId,
              previousSessionId,
              surveys.profiling.questionId,
              "some-answer")
            _ <- TestFixtures.createAnswer(
              testUser.userId,
              previousSessionId,
              surveys.location.questionId,
              "some-state")
            backend    <- testBackend
            localeResp <- putSetLocale(backend, "es")
            cookie = extractSessionCookie(localeResp).get
            _ <- TestFixtures.linkSessionToUser(user.SessionId.unsafe(cookie), testUser.userId)
            nextResp <- getNextSurvey(backend, cookie)
          yield assertTrue(
            nextResp.code.isSuccess,
            nextResp.body.contains("\"type\":\"step\""),
            nextResp.body.contains("\"phase\":\"advertiser_video\""))
        ,
        test("user who completed all surveys transitions to advertiser video phase"):
          for
            surveys  <- TestFixtures.seedAllIdentificationSurveys
            testUser <- TestFixtures.createUser("ready@example.com")
            _        <- TestFixtures.createAnswer(
              testUser.userId,
              previousSessionId,
              surveys.email.questionId,
              "ready@example.com")
            _ <- TestFixtures.createAnswer(
              testUser.userId,
              previousSessionId,
              surveys.profiling.questionId,
              "some-answer")
            _ <- TestFixtures.createAnswer(
              testUser.userId,
              previousSessionId,
              surveys.location.questionId,
              "some-state")
            backend    <- testBackend
            localeResp <- putSetLocale(backend, "es")
            cookie    = extractSessionCookie(localeResp).get
            sessionId = user.SessionId.unsafe(cookie)
            _           <- TestFixtures.linkSessionToUser(sessionId, testUser.userId)
            _           <- TestFixtures.updateSessionPhase(sessionId, Phase.AdvertiserVideo)
            finalStatus <- getStatus(backend, Some(cookie))
          yield assertTrue(
            finalStatus.code.isSuccess,
            finalStatus.body.contains("advertiser_video"))
      )
end SurveyProgressionSuite
