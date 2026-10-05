package whitelabel.captal.api.suites

import whitelabel.captal.api.TestFixtures
import whitelabel.captal.api.TestHelpers.*
import whitelabel.captal.core.survey.question.*
import whitelabel.captal.core.user
import zio.ZIO
import zio.test.*

/** V20 added `is_active` to questions, options, rules and localized texts. The column alone changes
  * nothing: what makes a soft delete real is the read path honouring it.
  *
  * So these assert against the portal's own HTTP surface rather than against the tables. Reverting
  * any one of the query filters added in V20 makes exactly one of them fail.
  */
object SoftDeleteSuite:
  // Answers seeded for a prior session, mirroring SurveyProgressionSuite: the pool of a category is
  // only exhausted by answers from an earlier visit.
  private val previousSessionId = user.SessionId.generate

  private def optionIdsOf(question: QuestionToAnswer): List[OptionId] =
    question.questionType match
      case QuestionType.Radio(options) =>
        options.map(_.id)
      case QuestionType.Checkbox(options, _) =>
        options.map(_.id)
      case QuestionType.Select(options) =>
        options.map(_.id)
      case _ =>
        Nil

  val suite: Spec[TestEnv, Throwable] =
    zio
      .test
      .suite("Soft Delete")(
        test("a deactivated question stops being offered"):
          for
            email     <- TestFixtures.seedEmailSurvey
            profiling <- TestFixtures.seedMultiQuestionProfilingSurvey
            testUser  <- TestFixtures.createUser("retired-question@example.com")
            _         <- TestFixtures.createAnswer(
              testUser.userId,
              previousSessionId,
              email.questionId,
              "retired-question@example.com")
            // The first one wins on displayOrder, so retiring it is what makes the assertion sharp:
            // without the filter the flow serves it and the second question is never reached.
            retired  = profiling.questions.head
            survivor = profiling.questions(1)
            _       <- TestFixtures.deactivateQuestion(retired)
            backend <- testBackend
            cookie  <- createSession(backend)
            _ <- TestFixtures.linkSessionToUser(user.SessionId.unsafe(cookie), testUser.userId)
            nextResp <- getNextSurvey(backend, cookie)
            parsed = parseNextSurvey(nextResp.body)
          yield assertTrue(
            nextResp.code.isSuccess,
            parsed.isDefined,
            parsed.get.question.id == survivor,
            parsed.get.question.id != retired)
        ,
        test("a deactivated option disappears from the question it belongs to"):
          for
            surveys <- TestFixtures.seedAllIdentificationSurveys
            options <- TestFixtures.addQuestionOptions(
              surveys.profiling.questionId,
              List("Under 30", "30 to 50", "Over 50"))
            testUser <- TestFixtures.createUser("retired-option@example.com")
            _        <- TestFixtures.createAnswer(
              testUser.userId,
              previousSessionId,
              surveys.email.questionId,
              "retired-option@example.com")
            _       <- TestFixtures.deactivateQuestionOption(options(1))
            backend <- testBackend
            cookie  <- createSession(backend)
            _ <- TestFixtures.linkSessionToUser(user.SessionId.unsafe(cookie), testUser.userId)
            nextResp <- getNextSurvey(backend, cookie)
            parsed = parseNextSurvey(nextResp.body)
            served = parsed.toList.flatMap(p => optionIdsOf(p.question))
          yield assertTrue(
            nextResp.code.isSuccess,
            parsed.isDefined,
            parsed.get.question.id == surveys.profiling.questionId,
            served.contains(options.head),
            !served.contains(options(1)),
            served.contains(options(2))
          )
        ,
        test("a deactivated localized text drops out of the advertised locales"):
          for
            _ <- TestFixtures.seedLocales(List("es", "en", "pt"))
            // seedLocales keys each row as `ui.locale.<locale>`.
            _        <- TestFixtures.deactivateLocalizedText("ui.locale.pt")
            backend  <- testBackend
            response <- getLocales(backend)
          yield assertTrue(
            response.code.isSuccess,
            response.body.contains("es"),
            response.body.contains("en"),
            !response.body.contains("pt"))
        ,
        test("a deactivated advertiser question is skipped rather than dropping the whole survey"):
          for
            _ <- TestFixtures.seedEmailSurvey
            a <- TestFixtures.seedAdvertiserWithVideoAndSurvey(
              advertiserName = "Advertiser A",
              videoUrl = "https://cdn.example.com/a.mp4",
              videoTitle = "Ad A",
              questionCount = 4)
            b <- TestFixtures.seedAdvertiserWithVideoAndSurvey(
              advertiserName = "Advertiser B",
              videoUrl = "https://cdn.example.com/b.mp4",
              videoTitle = "Ad B",
              questionCount = 4)
            // Two advertisers, four questions each, on top of the three noise advertiser surveys
            // seeded before every test: the query has to pick the right survey *and* the right
            // question inside it.
            //
            // Which video gets served is a priority-weighted random draw, so the two lowest
            // displayOrder questions are retired in BOTH surveys. Whichever advertiser wins, the
            // expected answer is its third question, and the assertion stays deterministic.
            fixtures = List(a, b)
            retired  = fixtures.flatMap(_.questionIds.take(2))
            _          <- ZIO.foreachDiscard(retired)(TestFixtures.deactivateQuestion)
            backend    <- testBackend
            cookie     <- createSession(backend)
            _          <- getNextSurvey(backend, cookie)
            _          <- postEmailAnswer(backend, cookie, "advertiser-join@example.com")
            _          <- getNextVideo(backend, cookie)
            _          <- postMarkVideoWatched(backend, cookie, 15, completed = true)
            surveyResp <- getNextAdvertiserSurvey(backend, cookie)
            parsed   = parseNextAdvertiserSurvey(surveyResp.body)
            expected = fixtures.map(f => (Some(f.advertiserId), f.surveyId, f.questionIds(2)))
            served   = parsed.map(p => (p.advertiserId.map(_.asString), p.surveyId, p.question.id))
          yield assertTrue(
            surveyResp.code.isSuccess,
            // This is the assertion that pins the join filter. Without it the query orders by
            // displayOrder, picks a retired question, the detail fetch (which does filter) returns
            // nothing, and the handler falls back to Phase.Ready — a successful response carrying
            // no survey at all. The survey would be silently lost, not just misordered.
            parsed.isDefined,
            served.exists(expected.contains),
            !parsed.exists(p => retired.contains(p.question.id))
          )
        ,
        test("a deactivated rule stops being enforced"):
          for
            email    <- TestFixtures.seedEmailSurvey
            ruled    <- TestFixtures.seedProfilingInputSurveyWithRules()
            testUser <- TestFixtures.createUser("retired-rule@example.com")
            _        <- TestFixtures.createAnswer(
              testUser.userId,
              previousSessionId,
              email.questionId,
              "retired-rule@example.com")
            backend <- testBackend
            cookie  <- createSession(backend)
            _ <- TestFixtures.linkSessionToUser(user.SessionId.unsafe(cookie), testUser.userId)
            nextResp <- getNextSurvey(backend, cookie)
            served = parseNextSurvey(nextResp.body)
            // "Ab" satisfies the pattern but is shorter than min_length = 8, so only that one rule
            // can reject it.
            before <- postProfilingTextAnswer(backend, cookie, "Ab")
            _      <- TestFixtures.deactivateQuestionRule(ruled.minLengthRuleId)
            after  <- postProfilingTextAnswer(backend, cookie, "Ab")
          yield assertTrue(
            served.exists(_.question.id == ruled.questionId),
            before.body.contains("text_too_short"),
            after.code.isSuccess,
            !after.body.contains("text_too_short"))
        ,
        test("deactivating one rule leaves the other one enforcing"):
          for
            email    <- TestFixtures.seedEmailSurvey
            ruled    <- TestFixtures.seedProfilingInputSurveyWithRules()
            testUser <- TestFixtures.createUser("one-rule@example.com")
            _        <- TestFixtures.createAnswer(
              testUser.userId,
              previousSessionId,
              email.questionId,
              "one-rule@example.com")
            backend <- testBackend
            cookie  <- createSession(backend)
            _ <- TestFixtures.linkSessionToUser(user.SessionId.unsafe(cookie), testUser.userId)
            _ <- getNextSurvey(backend, cookie)
            _ <- TestFixtures.deactivateQuestionRule(ruled.minLengthRuleId)
            // This is what a one-rule fixture could never show: with min_length retired, "ab" is
            // still rejected — by the pattern, and only by the pattern. A predicate that dropped
            // every rule of the question would let it through.
            lower <- postProfilingTextAnswer(backend, cookie, "ab")
            upper <- postProfilingTextAnswer(backend, cookie, "Ab")
          yield assertTrue(
            lower.body.contains("invalid_pattern"),
            !lower.body.contains("text_too_short"),
            upper.code.isSuccess)
        ,
        test("Required is unaffected by retiring every rule of the question"):
          for
            email    <- TestFixtures.seedEmailSurvey
            ruled    <- TestFixtures.seedProfilingInputSurveyWithRules()
            testUser <- TestFixtures.createUser("still-required@example.com")
            _        <- TestFixtures.createAnswer(
              testUser.userId,
              previousSessionId,
              email.questionId,
              "still-required@example.com")
            backend <- testBackend
            cookie  <- createSession(backend)
            _ <- TestFixtures.linkSessionToUser(user.SessionId.unsafe(cookie), testUser.userId)
            _ <- getNextSurvey(backend, cookie)
            _ <- TestFixtures.deactivateQuestionRule(ruled.minLengthRuleId)
            _ <- TestFixtures.deactivateQuestionRule(ruled.patternRuleId)
            // Required comes from questions.is_required, not from question_rules. Editing
            // validations in the backoffice must never quietly make a mandatory field optional.
            empty <- postProfilingTextAnswer(backend, cookie, "")
            // And "a" now passes, which is what proves both rules really were retired.
            ok <- postProfilingTextAnswer(backend, cookie, "a")
          yield assertTrue(empty.body.contains("required_answer_missing"), ok.code.isSuccess)
      )
end SoftDeleteSuite
