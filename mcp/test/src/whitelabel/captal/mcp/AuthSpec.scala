package whitelabel.captal.mcp

import com.jamesward.ziohttp.mcp.auth.{AuthError, OauthScope}
import zio.test.*

object AuthSpec extends ZIOSpecDefault:
  private val testAudience = com
    .jamesward
    .ziohttp
    .mcp
    .auth
    .ResourceUri
    .unsafe("https://mcp.test/mcp")
  private val Spec = "s3cret|alice|i18n:write,content:write;other|bob|i18n:write"

  def spec =
    suite("Auth")(
      suite("parsing")(
        test("reads operators and their scopes"):
          val parsed = Auth.parse(Spec)
          assertTrue(
            parsed.isRight,
            parsed.toOption.get.map(_._2.name) == List("alice", "bob"),
            parsed.toOption.get.head._2.scopes ==
              Set(OauthScope("i18n:write"), OauthScope("content:write")),
            parsed.toOption.get(1)._2.scopes == Set(OauthScope("i18n:write"))
          )
        ,
        test("an operator with no scopes is allowed and gets none"):
          val parsed = Auth.parse("tok|reader|")
          assertTrue(parsed.isRight, parsed.toOption.get.head._2.scopes.isEmpty)
        ,
        test("a malformed entry fails rather than being skipped"):
          // A dropped entry would look exactly like a revoked operator.
          assertTrue(Auth.parse("s3cret|alice").isLeft, Auth.parse("").isLeft)
        ,
        test("reusing one secret for two operators is refused"):
          assertTrue(Auth.parse("same|alice|;same|bob|").isLeft)
      ),
      suite("verification")(
        test("a known token yields a principal carrying its scopes"):
          val operators = Auth.parse(Spec).toOption.get
          for principal <- Auth.verifier(operators, testAudience).verify("s3cret")
          yield assertTrue(
            principal.subject.contains("alice"),
            principal.hasScope(OauthScope("content:write")))
        ,
        test("bob cannot reach content:write"):
          val operators = Auth.parse(Spec).toOption.get
          for principal <- Auth.verifier(operators, testAudience).verify("other")
          yield assertTrue(
            principal.hasScope(OauthScope("i18n:write")),
            !principal.hasScope(OauthScope("content:write")))
        ,
        test("an unknown token is rejected without saying why"):
          val operators = Auth.parse(Spec).toOption.get
          for result <- Auth.verifier(operators, testAudience).verify("guess").either
          yield assertTrue(
            result.isLeft,
            result
              .left
              .toOption
              .exists {
                case AuthError.Invalid(reason) =>
                  // Must not hint at which operator or how close the guess was.
                  !reason.contains("alice") && !reason.contains("s3cret")
                case _ =>
                  false
              }
          )
        ,
        test("a token that is a prefix of a real one is rejected"):
          val operators = Auth.parse(Spec).toOption.get
          for result <- Auth.verifier(operators, testAudience).verify("s3cre").either
          yield assertTrue(result.isLeft)
      ),
      suite("scopes on tools")(
        test("every write tool declares a scope and every read tool declares none"):
          val writes = List(
            I18nTools.setTranslations,
            I18nTools.removeTranslation,
            AdvertiserTools.create,
            AdvertiserTools.update,
            AdvertiserTools.setActive,
            SurveyTools.createSurvey,
            SurveyTools.createQuestion,
            SurveyTools.setQuestionActive,
            SurveyTools.reorder
          )
          val reads = List(
            I18nTools.status,
            AdvertiserTools.list,
            SurveyTools.list,
            SurveyTools.get)
          assertTrue(
            writes.forall(_.requiredScopes.nonEmpty),
            // A read needing no scope is what lets an operator find their way around before asking
            // for more access.
            reads.forall(_.requiredScopes.isEmpty)
          )
        ,
        test("translation and content writes are separate scopes"):
          assertTrue(
            I18nTools.setTranslations.requiredScopes == Set(Auth.I18nWrite),
            SurveyTools.createQuestion.requiredScopes == Set(Auth.ContentWrite),
            !I18nTools.setTranslations.requiredScopes.contains(Auth.ContentWrite)
          )
      )
    )
end AuthSpec
