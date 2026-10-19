package whitelabel.captal.mcp

import com.jamesward.ziohttp.mcp.*
import com.jamesward.ziohttp.mcp.auth.McpAuth
import whitelabel.captal.core.i18n.{I18n, I18nSchema}
import whitelabel.captal.mcp.apps.UploadUiSource
import whitelabel.captal.mcp.aws.{AssetStore, InfraStore}
import whitelabel.captal.mcp.protocol.{Skill, Skills, SkillsExtension, ToolSchema}
import whitelabel.captal.mcp.resources.{EntityResources, Readiness}
import whitelabel.captal.mcp.tools.{
  AdvertiserTools,
  AssetTools,
  DeployTools,
  I18nTools,
  LocationTools,
  Prompts,
  SkillTools,
  SurveyTools,
  TokenTools,
  UnifiTools,
  VideoTools
}
import whitelabel.captal.platform.schema.QuillSqlite
import zio.{Chunk, IO, ZEnvironment, ZIO}

import ToolSchema.visibleTo

/** Assembles the backoffice server: its instructions, resources, prompts, tools and extensions.
  *
  * Skills are served as resources under `skill://` and also over `skills/list` and `skills/get` —
  * see [[SkillsExtension]].
  */
object CaptalMcp:
  val RequiredI18nKeysUri: String = "i18n://required-keys"

  /** The server instructions, loaded into every conversation before any tool schema — so an index
    * and not a manual.
    */
  val instructions: String =
    s"""Backoffice for the Captal whitelabel captive portal.
       |
       |Content is edited here: translations, surveys and questions, advertisers, videos, and each
       |location's stylesheet and brand icon. A location's portal is also stood up here, with
       |`deploy_location`. Tearing one down is not — nor is the shared infrastructure every location
       |sits on, which lives in its own repository and is applied with OpenTofu.
       |
       |Call `read_skill` before acting on a request in its area. Each skill is a procedure, not
       |background reading, and they encode constraints that are not visible from the data alone;
       |the tool's description lists what each one covers.
       |
       |Start here for a location that is not finished: `get_location_readiness` reads its current
       |state and names the next steps in order. Call it again between steps rather than tracking
       |the state yourself — every tool writes immediately, so there is no commit step and stopping
       |part way leaves nothing broken. The `onboard-location` prompt is the same report, for an
       |operator who wants to start from it.
       |
       |An upload interrupted halfway leaves a video registered with no file, invisible to guests
       |and cheap to finish: `list_pending_uploads` finds them and `resume_video_upload` reopens
       |one. Worth checking when a conversation about videos resumes.
       |
       |This server also publishes resources — the entities under `captal://`, the skills under
       |`skill://`, `$RequiredI18nKeysUri` — which an operator can attach and some hosts let a model
       |read. Do not depend on reaching them: every one of them is also answerable by a tool, and in
       |the host these operators use, resources never reach the model at all.
       |""".stripMargin

  private def skillResource(skill: Skill): McpResourceHandler = McpResource(skill.uri, skill.name)
    .description(skill.description)
    .mimeType("text/markdown")
    .read(uri => ZIO.succeed(Chunk(ResourceContents(uri, Some("text/markdown"), Some(skill.body)))))

  /** `i18n://required-keys`: every key plus what it is for, both derived — the list from the type,
    * the descriptions from an [[I18n]] instance the compiler forces to be total.
    */
  private val requiredI18nKeys: McpResourceHandler = McpResource(
    RequiredI18nKeysUri,
    "Required i18n keys")
    .description(
      "Every translation key the portal requires, in declaration order, with what each one is for. Derived from the I18n schema.")
    .mimeType("text/plain")
    .read: uri =>
      val described = I18nSchema.flatten(I18n.descriptions)
      val body = I18nSchema
        .keys[I18n]
        .map(key => s"$key: ${described.getOrElse(key, "")}")
        .mkString("\n")
      ZIO.succeed(Chunk(ResourceContents(uri, Some("text/plain"), Some(body))))

  val resources: List[McpResourceHandler] = requiredI18nKeys :: Skills.all.map(skillResource)

  /** Tasks, Skills and Apps, validated into one registry.
    *
    * An effect because the Tasks runtime owns the task store and the `Ref`s holding running fibers.
    * The store is in-memory: tasks live in this process, so `tasks/get` only answers on the replica
    * that started the work. Fails when two ids collide or an operation shadows a core method.
    */
  val extensions: IO[IllegalStateException, McpExtensions[Any]] = McpTasks
    .inMemory
    .flatMap: tasks =>
      ZIO
        .fromEither((tasks ++ SkillsExtension.extension).flatMap(_.add(McpApps.serverExtension)))
        .mapError(error =>
          new IllegalStateException(s"Could not build the MCP extension registry: $error"))

  /** Auth is not optional: the SDK silently ignores every `requireScopes` declaration when no
    * `.auth(...)` is configured, leaving every tool open to anyone who can reach the port.
    */
  def server(
      // Reaches the database, where the tokens live.
      auth: McpAuth[QuillSqlite],
      // Prompts alone need the services in hand: `McpPromptHandler.get` returns `ZIO[Any, ...]`, so
      // it has no environment to ask a database for. Everything else is resolved by the runtime.
      promptEnv: ZEnvironment[QuillSqlite & AssetStore],
      // The origin the upload page PUTs to, declared in that page's CSP. Resolved from the same
      // store that signs the URLs, so the policy and the URL cannot disagree.
      uploadOrigin: String,
      // Where the pages' own modules come from, declared in their `resourceDomains`. The same CDN
      // the portal's assets are served from; `client.publish` puts them under `ui/`.
      scriptOrigin: String,
      // Where this server is reached. The pages it renders post there and declare it in their CSP,
      // which belongs to them rather than to the protocol; see [[UiRoutes]].
      uiOrigin: String,
      // Absent in a deployment with no `ECS_CLUSTER`, and then the deploy tools are not registered
      // at all.
      infra: Option[InfraStore],
      // Apps, Skills and Tasks, already validated; built in [[Main]], since the Tasks runtime is an
      // effect and this assembly is pure.
      extensions: McpExtensions[Any],
      name: String = "captal-backoffice",
      version: String = "0.1.0"): McpServer[QuillSqlite & AssetStore] =
    /** Default-deny for the rendered pages: a tool is callable from an App only by being named
      * here, where the extension's own default is callable by both the model and any App.
      *
      * `confirm_video_upload` is the whole list — the upload page calls it with the duration it
      * read from the file, and the model calls it for a WebM whose header carries none.
      */
    def restrictToApps[R](tool: McpToolHandlerR[R]): McpToolHandlerR[R] =
      if tool.name.value == "confirm_video_upload" then
        tool.visibleTo(McpAppsVisibility.Model, McpAppsVisibility.App)
      else
        tool.visibleTo(McpAppsVisibility.Model)
    val withResources =
      resources.foldLeft(McpServer(name, version).instructions(instructions).auth(auth))(
        _.resource(_))
    val withPrompts = Prompts.all(promptEnv).foldLeft(withResources)(_.prompt(_))
    val withData = (
      LocationTools.all ++ I18nTools.all ++ AdvertiserTools.all ++ SurveyTools.all ++
        UnifiTools.all(uiOrigin) ++ TokenTools.all ++ SkillTools.all
    ).map(restrictToApps).foldLeft[McpServer[QuillSqlite]](withPrompts)(_.tool(_))
    // `deploy_location` is here rather than above because it copies the shared bundle under the new
    // slug, which is S3 and therefore `AssetStore`.
    val withAssets = (AssetTools.all ++ VideoTools.all ++ Readiness.all ++ DeployTools.all(infra))
      .map(restrictToApps)
      .foldLeft[McpServer[QuillSqlite & AssetStore]](withData)(_.tool(_))
    // One source for everything: `McpServer` holds a single `resourceSrc`, so the upload page and
    // the entity tree cannot be registered separately.
    val withSource = withAssets.resourceSource(
      EntityResources(UploadUiSource(uploadOrigin, scriptOrigin), uiOrigin, scriptOrigin))
    // The registry carries `io.modelcontextprotocol/ui`, which is how a host knows the `ui://`
    // resources are meant to be rendered rather than read; `…/skills`, which makes the bundled
    // documents discoverable; and `…/tasks`, without which a tool's `taskExecution` is inert and
    // every call answers synchronously. `withExtensions` replaces rather than merges, so the whole
    // registry is assembled in one place and handed over whole.
    withSource.withExtensions(extensions)
  end server
end CaptalMcp
