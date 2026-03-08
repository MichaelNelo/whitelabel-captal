package whitelabel.captal.api.suites

import whitelabel.captal.api.TestHelpers.*
import whitelabel.captal.api.{TestFixtures, TestHelpers}
import whitelabel.captal.core.application.{IdentificationSurveyType, Phase}
import whitelabel.captal.core.user
import zio.test.*

object SessionIsolationSuite:
  // Use a different sessionId for "previous session" answers (simulating a past visit)
  private val previousSessionId = user.SessionId.generate

  val suite: Spec[TestEnv, Throwable] =
    zio
      .test
      .suite("Session Isolation")(
        test("new session uses its own question assignment, not old session's"):
          // Regression test for bug where UserRepositoryQuill.findAnswering()
          // was joining with ANY session of the user instead of the CURRENT session.
          // This caused the system to validate answers against wrong question.
          //
          // Original bug scenario:
          // - User has multiple sessions (lost cookie, different devices, etc.)
          // - Session 1 has questionId = Q1 (input type)
          // - Session 2 has questionId = Q2 (radio type)
          // - When answering in session 2, system validated against Q1 instead of Q2
          //
          // This test simulates:
          // 1. User already answered email in a previous session
          // 2. Session 1: getNextSurvey assigns profiling Q1 to session1
          // 3. User loses session WITHOUT answering
          // 4. Session 2: getNextSurvey assigns profiling Q1 to session2
          // 5. Answer in session 2 - should validate against session2's Q1, not session1's
          for
            // Setup: surveys with multi-question profiling
            emailSurvey     <- TestFixtures.seedEmailSurvey
            profilingSurvey <- TestFixtures.seedMultiQuestionProfilingSurvey
            // Add options to all profiling questions
            optionsQ1 <- TestFixtures.addQuestionOptions(
              profilingSurvey.questions(0),
              List("18-25", "26-35", "36-45"))
            _ <- TestFixtures.addQuestionOptions(
              profilingSurvey.questions(1),
              List("Male", "Female", "Other"))
            _ <- TestFixtures.addQuestionOptions(
              profilingSurvey.questions(2),
              List("Engineer", "Doctor", "Teacher"))

            // Create a user who has already answered email in a PREVIOUS session
            testUser <- TestFixtures.createUser("isolation-test@example.com")
            _ <- TestFixtures.createAnswer(
              testUser.userId,
              previousSessionId,
              emailSurvey.questionId,
              "isolation-test@example.com")

            backend <- testBackend

            // === Session 1: get profiling question (but DON'T answer) ===
            firstCookie <- createSession(backend)
            _           <- TestFixtures.linkSessionToUser(
              user.SessionId.unsafe(firstCookie),
              testUser.userId)
            _ <- TestFixtures.updateSessionPhase(
              user.SessionId.unsafe(firstCookie),
              Phase.IdentificationQuestion)
            // This assigns profiling Q1 to session1
            firstNextResp <- getNextSurvey(backend, firstCookie)
            firstParsed = parseNextSurvey(firstNextResp.body)

            // === Session 2: same user, new session ===
            secondCookie <- createSession(backend)
            _            <- TestFixtures.linkSessionToUser(
              user.SessionId.unsafe(secondCookie),
              testUser.userId)
            _ <- TestFixtures.updateSessionPhase(
              user.SessionId.unsafe(secondCookie),
              Phase.IdentificationQuestion)
            // This assigns profiling Q1 to session2
            secondNextResp <- getNextSurvey(backend, secondCookie)
            secondParsed = parseNextSurvey(secondNextResp.body)

            // Answer in session2 - THE KEY TEST
            // Bug: system validated against session1's question
            // Fix: system now correctly uses session2's question
            answerResp <- postProfilingAnswer(backend, secondCookie, optionsQ1.head.asString)
          yield assertTrue(
            // Both sessions should get profiling Q1 (first unanswered profiling question)
            firstParsed.isDefined,
            firstParsed.get.surveyType == IdentificationSurveyType.Profiling,
            firstParsed.get.question.id == profilingSurvey.questions(0),
            secondParsed.isDefined,
            secondParsed.get.surveyType == IdentificationSurveyType.Profiling,
            secondParsed.get.question.id == profilingSurvey.questions(0),
            // Answer should succeed in session2
            answerResp.code.isSuccess,
            // Should NOT contain incompatible_answer_type error
            !answerResp.body.contains("incompatible_answer_type")
          )
      )
end SessionIsolationSuite
