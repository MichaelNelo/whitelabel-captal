package whitelabel.captal.api.suites

import whitelabel.captal.api.TestFixtures
import whitelabel.captal.api.TestHelpers.*
import whitelabel.captal.core.application.{IdentificationSurveyType, Phase}
import whitelabel.captal.core.survey.question.*
import whitelabel.captal.core.user
import zio.test.*

object EmailSurveySuite:
  val suite: Spec[TestEnv, Throwable] =
    zio
      .test
      .suite("Email Survey")(
        test("anonymous user is offered email survey with correct QuestionToAnswer"):
          for
            seeded   <- TestFixtures.seedEmailSurvey
            backend  <- testBackend
            cookie   <- createSession(backend)
            nextResp <- getNextSurvey(backend, cookie)
            parsed = parseNextSurvey(nextResp.body)
          yield assertTrue(
            nextResp.code.isSuccess,
            parsed.isDefined,
            parsed.get.surveyId == seeded.surveyId,
            parsed.get.surveyType == IdentificationSurveyType.Email,
            parsed.get.question.id == seeded.questionId,
            parsed.get.question.questionType match
              case QuestionType.Input(rules) =>
                rules.contains(TextRule.Email)
              case _ =>
                false
            ,
            parsed.get.question.commonRules.contains(CommonRule.Required)
          )
        ,
        test("valid email answer creates user and transitions to advertiser video phase"):
          val testEmail = user.Email.unsafeFrom("user@example.com")
          for
            _         <- TestFixtures.seedEmailSurvey
            backend   <- testBackend
            cookie    <- createSession(backend)
            _         <- getNextSurvey(backend, cookie)
            emailResp <- postEmailAnswer(backend, cookie, "user@example.com")
            dbState   <- TestFixtures.queryDbState
          yield assertTrue(
            emailResp.code.isSuccess,
            dbState.users.exists(_.email.contains(testEmail)),
            dbState.answers.nonEmpty,
            dbState.sessions.exists(_.phase == Phase.AdvertiserVideo),
            dbState.progress.nonEmpty
          )
        ,
        test("invalid email format is rejected with validation error"):
          for
            _         <- TestFixtures.seedEmailSurvey
            backend   <- testBackend
            cookie    <- createSession(backend)
            _         <- getNextSurvey(backend, cookie)
            emailResp <- postEmailAnswer(backend, cookie, "not-an-email")
          yield assertTrue(
            !emailResp.code.isSuccess || emailResp.body.contains("error"),
            emailResp.body.contains("invalid_email"))
      )
end EmailSurveySuite
