package whitelabel.captal.mcp

import com.jamesward.ziohttp.mcp.McpToolHandlerR
import zio.test.*

/** Which tools say they change nothing.
  *
  * A resource is context a host may hand to a model freely; a tool call is an act it asks the
  * operator to approve. This server answers most of its questions through tools, because the
  * resources that answer them do not reach the model in the hosts these operators use — so the only
  * thing separating "what does this location still need" from "revoke this person's credentials" is
  * `readOnlyHint`.
  *
  * Pinned as a partition rather than a list of names: every tool is classified, and the two sides
  * are derived from different things — the annotation on one, the scopes and the name on the other
  * — so a new tool cannot join by being forgotten.
  */
object AnnotationsSpec extends ZIOSpecDefault:

  private def all(uiOrigin: String): List[McpToolHandlerR[?]] =
    LocationTools.all ++ I18nTools.all ++ AdvertiserTools.all ++ SurveyTools.all ++
      AssetTools.all ++ VideoTools.all ++ TokenTools.all ++ SkillTools.all ++ Readiness.all ++
      UnifiTools.all(uiOrigin)

  private val tools = all("http://localhost:8081")

  private def isReadOnly(tool: McpToolHandlerR[?]) = tool
    .definition
    .annotations
    .flatMap(_.readOnlyHint)
    .contains(true)

  def spec =
    suite("tool annotations")(
      // Named by convention and classified by annotation; the two have to agree. A `get_` that
      // writes, or a `create_` that claims to read, is caught here rather than by an operator
      // wondering why nothing asked them.
      test("every read is declared read-only and nothing else is"):
        val (reads, writes) = tools.partition(t =>
          t.name.value.startsWith("list_") || t.name.value.startsWith("get_") ||
            t.name.value.startsWith("read_"))
        assertTrue(
          reads.nonEmpty,
          writes.nonEmpty,
          reads.forall(isReadOnly),
          writes.forall(t => !isReadOnly(t)))
      ,
      // The cautious default is deliberate: absent, `destructiveHint` is true. Declaring a write
      // read-only would be the one mistake here that cannot be noticed from the outside — the
      // approval simply stops being asked for.
      test("no tool that requires a write scope claims to be read-only"):
        val writeScoped = tools.filter(_.requiredScopes.nonEmpty)
        assertTrue(writeScoped.nonEmpty, writeScoped.forall(t => !isReadOnly(t)))
      ,
      // The two whose whole point is being called repeatedly. If either costs an approval each
      // time, the advice they carry — read the procedure first, re-read the state between steps —
      // stops being worth following.
      test("the two meant to be called constantly are read-only"):
        assertTrue(isReadOnly(SkillTools.read), isReadOnly(Readiness.tool))
    )
end AnnotationsSpec
