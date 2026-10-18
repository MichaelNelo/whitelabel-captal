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

/** The three extensions this server declares, asserted where each one can actually be got wrong.
  *
  * All three are capability declarations, which is a category of bug with no symptom locally: a
  * missing one does not fail, it degrades. Tasks go quiet and answer synchronously, skills stop
  * being discoverable, Apps resources come back as documents to display. So each of these pins the
  * declaration rather than the behaviour behind it.
  */
object ExtensionsSpec extends ZIOSpecDefault:

  def spec =
    suite("extensions")(
      test("the registry carries Apps, Skills and Tasks, and validates"):
        // Validation is the point of building it once at startup: ids must be unique and an
        // extension's operations must not shadow a core method. `skills/get` is one typo away from
        // `resources/get`, and the registry is what refuses rather than silently winning.
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
        // The half that makes this an extension and not just a capability string. Tasks declares no
        // operations of its own — `tasks/get` and friends are core methods the server dispatches
        // once a runtime is registered — so only Skills contributes any here.
        for registered <- CaptalMcp.extensions
        yield assertTrue(
          registered.operation(McpSkills.ListMethod).isDefined,
          registered.operation(McpSkills.GetMethod).isDefined,
          // Not registered, because every skill here is a single document: see
          // [[SkillsExtension.extension]].
          registered.operation(McpSkills.DirectoryReadMethod).isEmpty
        )
      ,
      suite("skills over the extension")(
        test("every bundled skill is published, none skipped"):
          // `McpSkillEntry.static` enforces the contract, and the one it is most likely to reject is
          // the frontmatter `name` having to equal the directory the skill lives in. `Skills.load`
          // falls back to the slug when the line is missing, so a drift between the two arrives
          // here looking almost right and is otherwise invisible — the skill just stops appearing.
          assertTrue(
            SkillsExtension.skipped == Nil,
            SkillsExtension.entries.size == Skills.all.size)
        ,
        test("each entry names the resource that carries its body, with a digest over that body"):
          // The entry is metadata; the content comes back through core `resources/read` on the same
          // URI. A digest over anything else — the file on disk, frontmatter included — would be a
          // digest of something no client can fetch, and a client that checks it would conclude the
          // server is serving a corrupted skill.
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
          // Answering a cursor from the top looks like progress to a client that is paginating, and
          // loops. Refusing is the only honest answer from a source that does not paginate.
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
            // Reported as bad parameters rather than as an internal error: the caller asked for
            // something that is not there, which is their problem to fix and not a server fault.
            unknown ==
              Left(
                McpSkillsSourceError.InvalidParams(
                  "No skill at 'skill://not-a-skill/SKILL.md'. Call skills/list, or read_skill for the same set as a tool."))
          )
          end for
      ),
      suite("task policy")(
        test("deploy_location requires a task, because it waits"):
          // This pin was `WhenSupported`, on the reasoning that five AWS calls which wait for
          // nothing are safe to answer in line. That stopped being true: the deploy now waits for a
          // task to run and for the load balancer to call the target healthy, up to three minutes,
          // which has no synchronous form worth offering. A client that cannot poll gets `-32021`
          // and has created nothing.
          //
          // Worth pinning rather than leaving to the comment, because the two differ only in what
          // happens on a host that does not declare the extension — nothing you would notice while
          // developing against one that does.
          assertTrue(
            DeployTools.declaration.toolTaskPolicy.execution == TaskExecution.Required,
            DeployTools.declaration.toolRequiredScopes == Set(Auth.InfraDeploy),
            // No store configured, no tool — the registration and the configuration it needs travel
            // together.
            DeployTools.all(None).isEmpty
          )
        ,
        test("the visibility wrapper does not drop the policy"):
          // The regression this exists for. `visibleTo` is a hand-written `McpToolHandlerR`, and
          // [[CaptalMcp]] wraps *every* tool in it; `taskPolicy` arrived on the trait with a
          // `TaskPolicy.never` default, so forgetting to forward it compiles, says nothing, and
          // turns every `taskExecution` in the server into a no-op.
          val declared = McpTool("probe")
            .description("probe")
            .taskExecution(TaskExecution.Required)
            .handle((_: Unit) => ZIO.succeed("ok"))
          assertTrue(
            declared.taskPolicy.execution == TaskExecution.Required,
            declared.visibleTo(McpAppsVisibility.Model).taskPolicy.execution ==
              TaskExecution.Required,
            // And the rest of the forwarding stays intact, which is the same failure one field over.
            declared.visibleTo(McpAppsVisibility.Model).name == declared.name
          )
        ,
        test("a synchronous caller is told what a timeout would and would not mean"):
          // Measured off the request rather than written in a document, because the answer differs
          // per client. Silent in the task case: a client holding a handle already knows, and a
          // reassurance on every successful deploy is how `nextSteps` becomes unread.
          val synchronous = DeployTools.taskNote(Map.empty)
          val asTask = DeployTools.taskNote(Map(McpTasks.Id -> Json.Obj()))
          assertTrue(
            asTask.isEmpty,
            synchronous.size == 1,
            // The actionable half: a lost reply is not a failed deploy, and the call is safe to
            // repeat. Without that an operator re-runs it nervously or, worse, starts deleting.
            synchronous.head.contains("deploy_location again"),
            synchronous.head.contains("idempotent")
          )
        ,
        test("a tool that declares nothing never becomes a task"):
          // The default, asserted because it is the safe one and because `never` is what keeps the
          // content tools answering in the same call.
          assertTrue(
            (I18nTools.all ++ AdvertiserTools.all ++ SurveyTools.all ++ VideoTools.all).forall(
              _.taskPolicy.execution == TaskExecution.Never))
      )
    )
end ExtensionsSpec
