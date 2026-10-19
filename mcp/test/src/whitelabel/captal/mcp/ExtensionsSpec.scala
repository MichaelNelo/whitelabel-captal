package whitelabel.captal.mcp

import com.jamesward.ziohttp.mcp.*
import whitelabel.captal.mcp.access.Auth
import whitelabel.captal.mcp.protocol.{Skills, SkillsExtension, ToolSchema}
import whitelabel.captal.mcp.tools.{
  AdvertiserTools,
  DeployTools,
  I18nTools,
  SurveyTools,
  VideoTools
}
import zio.json.ast.Json
import zio.test.*
import zio.{Chunk, ZIO}

import ToolSchema.visibleTo

/** The three extensions this server declares — Apps, Skills and Tasks. Each test pins the
  * capability declaration rather than the behaviour behind it.
  */
object ExtensionsSpec extends ZIOSpecDefault:

  def spec =
    suite("extensions")(
      test("the registry carries Apps, Skills and Tasks, and validates"):
        // Built once at startup: ids must be unique and an extension's operations must not shadow a
        // core method.
        for registered <- CaptalMcp.extensions
        yield
          val ids = registered.values.map(_.id.value).toSet
          assertTrue(
            ids ==
              Set(
                "io.modelcontextprotocol/ui",
                "io.modelcontextprotocol/skills",
                "io.modelcontextprotocol/tasks"))
      ,
      test("skills/list and skills/get are reachable as operations"):
        // Tasks declares no operations of its own — `tasks/get` and friends are core methods — so
        // only Skills contributes any here.
        for registered <- CaptalMcp.extensions
        yield assertTrue(
          registered.operation(McpSkills.ListMethod).isDefined,
          registered.operation(McpSkills.GetMethod).isDefined,
          // Not registered: every skill here is a single document.
          registered.operation(McpSkills.DirectoryReadMethod).isEmpty
        )
      ,
      suite("skills over the extension")(
        test("every bundled skill is published, none skipped"):
          // `McpSkillEntry.static` requires the frontmatter `name` to equal the skill's directory.
          assertTrue(
            SkillsExtension.skipped == Nil,
            SkillsExtension.entries.size == Skills.all.size)
        ,
        test("each entry names the resource that carries its body, with a digest over that body"):
          // The entry is metadata; the body comes back through core `resources/read` on the same
          // URI, and the digest is taken over that served body.
          val served = CaptalMcp.resources.map(_.definition.uri).toSet
          val resources = SkillsExtension
            .entries
            .flatMap: entry =>
              entry.resources match
                case McpSkillResources.Static(values) =>
                  values.toChunk
                case McpSkillResources.Dynamic =>
                  Chunk.empty
          assertTrue(
            resources.size == SkillsExtension.entries.size,
            resources.forall(resource => served.contains(resource.uri.value)),
            resources.forall(_.digest.value.startsWith("sha256:")),
            resources.forall(_.size.bytes > 0)
          )
        ,
        test("list answers the whole set, and refuses a cursor it never issued"):
          val ctx = McpRequestContext(ProtocolVersion.default)
          for
            listed  <- SkillsExtension.source.list(McpSkillsListParams(None), ctx)
            paged   <- SkillsExtension.source.list(McpSkillsListParams(Some("page-2")), ctx).either
            known   <- SkillsExtension.source.get(SkillsExtension.entries.head.uri, ctx)
            unknown <- ZIO
              .fromEither(McpSkillUri.parse("skill://not-a-skill/SKILL.md"))
              .mapError(error => new IllegalStateException(s"$error"))
              .orDie
              .flatMap(uri => SkillsExtension.source.get(uri, ctx).either)
          yield assertTrue(
            listed.skills.size == Skills.all.size,
            listed.nextCursor.isEmpty,
            paged.isLeft,
            known.uri == SkillsExtension.entries.head.uri,
            // Bad parameters rather than an internal error.
            unknown ==
              Left(
                McpSkillsSourceError.InvalidParams(
                  "No skill at 'skill://not-a-skill/SKILL.md'. Call skills/list, or read_skill for the same set as a tool."))
          )
          end for
      ),
      suite("task policy")(
        test("deploy_location requires a task, because it waits"):
          assertTrue(
            DeployTools.declaration.toolTaskPolicy.execution == TaskExecution.Required,
            DeployTools.declaration.toolRequiredScopes == Set(Auth.InfraDeploy),
            // No store configured, no tool.
            DeployTools.all(None).isEmpty
          )
        ,
        test("the visibility wrapper does not drop the policy"):
          // `visibleTo` is a hand-written `McpToolHandlerR` that [[CaptalMcp]] wraps every tool in;
          // `taskPolicy` has a `TaskPolicy.never` default, so a missing forward is silent.
          val declared = McpTool("probe")
            .description("probe")
            .taskExecution(TaskExecution.Required)
            .handle((_: Unit) => ZIO.succeed("ok"))
          assertTrue(
            declared.taskPolicy.execution == TaskExecution.Required,
            declared.visibleTo(McpAppsVisibility.Model).taskPolicy.execution ==
              TaskExecution.Required,
            declared.visibleTo(McpAppsVisibility.Model).name == declared.name
          )
        ,
        test("a synchronous caller is told what a timeout would and would not mean"):
          // Measured off the request: silent when called as a task, one note when synchronous.
          val synchronous = DeployTools.taskNote(Map.empty)
          val asTask = DeployTools.taskNote(Map(McpTasks.Id -> Json.Obj()))
          assertTrue(
            asTask.isEmpty,
            synchronous.size == 1,
            // A lost reply is not a failed deploy, and the call is safe to repeat.
            synchronous.head.contains("deploy_location again"),
            synchronous.head.contains("idempotent")
          )
        ,
        test("a tool that declares nothing never becomes a task"):
          assertTrue(
            (I18nTools.all ++ AdvertiserTools.all ++ SurveyTools.all ++ VideoTools.all).forall(
              _.taskPolicy.execution == TaskExecution.Never))
      )
    )
end ExtensionsSpec
