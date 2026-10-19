package whitelabel.captal.mcp

import com.jamesward.ziohttp.mcp.McpToolHandlerR
import whitelabel.captal.mcp.resources.Readiness
import whitelabel.captal.mcp.tools.{
  AdvertiserTools,
  AssetTools,
  I18nTools,
  LocationTools,
  SkillTools,
  SurveyTools,
  TokenTools,
  UnifiTools,
  VideoTools
}
import zio.test.*

/** Which tools say they change nothing. Every tool is classified, so a new one cannot join by being
  * forgotten.
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
      // Named by convention and classified by annotation; the two have to agree.
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
      test("no tool that requires a write scope claims to be read-only"):
        val writeScoped = tools.filter(_.requiredScopes.nonEmpty)
        assertTrue(writeScoped.nonEmpty, writeScoped.forall(t => !isReadOnly(t)))
      ,
      test("the two meant to be called constantly are read-only"):
        assertTrue(isReadOnly(SkillTools.read), isReadOnly(Readiness.tool))
    )
end AnnotationsSpec
