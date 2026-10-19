package whitelabel.captal.api.suites

import whitelabel.captal.api.TestFixtures
import whitelabel.captal.api.TestHelpers.*
import whitelabel.captal.core.user
import zio.test.*

/** A rule the portal cannot read is dropped, and says so.
  *
  * Dropping it is deliberate — a question with one unreadable rule is better asked than withheld —
  * but it used to happen in silence, through a `.toOption` that threw the reason away. A validation
  * that stops being applied looks exactly like one that passed: same 200, same question, no error
  * anywhere. The only way to notice was to compare the `question_rules` table against the observed
  * behaviour by hand.
  *
  * So these tests are about the log line, which is the whole fix. They read it from `ZTestLogger`,
  * which the zio-test runtime installs by default, rather than asserting on a response — the
  * response is identical either way, and that is precisely the problem.
  *
  * Each case has to get the guest as far as the question carrying the bad rule, which is why the
  * setups differ: an anonymous visitor is offered the email survey and nothing else, so a rule on a
  * profiling question is never read at all until the email one is answered.
  */
object UnreadableRuleSuite:

  /** Valid JSON, valid as *some* rule, and not as a rule of an `input` question.
    *
    * `{"MinSelections":{"min":2}}` is what `surveySchemas.encode` writes for a `SelectionRule`, so
    * this is not a corrupt row — it is the shape a checkbox question's rule has, attached to a
    * question that takes text rules. The realistic way to get one: a question's type edited after
    * its rules were written.
    */
  private val SelectionRuleOnAnInputQuestion = """{"MinSelections":{"min":2}}"""

  /** Answers seeded for a prior session, mirroring `SurveyProgressionSuite`: the email pool is only
    * exhausted by an answer from an earlier visit, which is what makes profiling the next survey.
    */
  private val previousSessionId = user.SessionId.generate

  private def warnings = ZTestLogger
    .logOutput
    .map(_.filter(_.logLevel == zio.LogLevel.Warning).map(_.message()).toList)

  val suite: Spec[TestEnv, Throwable] =
    zio
      .test
      .suite("Unreadable rules")(
        test("a rule that does not decode is named in a warning, with its reason"):
          // On the email question, which is what an anonymous visitor is served first.
          for
            fixture <- TestFixtures.seedEmailSurvey
            ruleId  <- TestFixtures.addQuestionRule(
              fixture.questionId,
              "text",
              SelectionRuleOnAnInputQuestion)
            backend  <- testBackend
            cookie   <- createSession(backend)
            response <- getNextSurvey(backend, cookie)
            logged   <- warnings
          yield
            val about = logged.filter(_.contains(ruleId))
            assertTrue(
              // The question is still served: dropping the rule does not withhold it.
              response.code.isSuccess,
              about.size == 1,
              about.head.contains(fixture.questionId.asString),
              about.head.contains("does not decode"),
              // The reason and the offending bytes, so the row can be found and fixed.
              about.head.contains(SelectionRuleOnAnInputQuestion)
            ) ?? s"warnings=$logged"
        ,
        test("a readable rule produces no warning at all"):
          // The other half: a suite that only asserted the presence of a warning would pass with a
          // handler that warned about every rule it read. `seedEmailSurvey` attaches a valid
          // `TextRule.Email` to an `input` question.
          for
            _        <- TestFixtures.seedEmailSurvey
            backend  <- testBackend
            cookie   <- createSession(backend)
            response <- getNextSurvey(backend, cookie)
            logged   <- warnings
          yield assertTrue(
            response.code.isSuccess,
            logged.forall(!_.contains("does not decode"))) ?? s"warnings=$logged"
        ,
        test("a rule attached to a type that takes none is reported as ignored"):
          // `parseQuestionType` builds a `Radio` from its options alone, so a rule row on a radio
          // question is dropped whatever it contains — a third way for a validation to vanish, and
          // the one least likely to be noticed, since the config itself is perfectly valid.
          for
            surveys  <- TestFixtures.seedAllIdentificationSurveys
            testUser <- TestFixtures.createUser("unreadable@example.com")
            _        <- TestFixtures.createAnswer(
              testUser.userId,
              previousSessionId,
              surveys.email.questionId,
              "unreadable@example.com")
            ruleId <- TestFixtures.addQuestionRule(
              surveys.profiling.questionId,
              "selection",
              SelectionRuleOnAnInputQuestion)
            backend <- testBackend
            cookie  <- createSession(backend)
            _ <- TestFixtures.linkSessionToUser(user.SessionId.unsafe(cookie), testUser.userId)
            response <- getNextSurvey(backend, cookie)
            logged   <- warnings
          yield
            val about = logged.filter(_.contains(ruleId))
            assertTrue(
              response.code.isSuccess,
              about.size == 1,
              about.head.contains("takes no rules")) ?? s"warnings=$logged"
      )
end UnreadableRuleSuite
