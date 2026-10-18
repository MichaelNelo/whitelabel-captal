package whitelabel.captal.mcp.tools

import com.jamesward.ziohttp.mcp.*
import whitelabel.captal.mcp.aws.AssetStore
import whitelabel.captal.mcp.resources.Readiness
import whitelabel.captal.platform.schema.QuillSqlite
import zio.*

/** Onboarding, as far as MCP can express a sequence.
  *
  * MCP has no workflow primitive, deliberately: tools are individual capabilities and the agent is
  * the orchestrator. A prompt is the closest thing the protocol offers, and it is not an executor —
  * `get` returns messages. It cannot call a tool, cannot ask for consent, cannot commit anything.
  *
  * What it can do is be *user-initiated* and *stateful*. Hosts surface prompts as commands the
  * operator picks, which a `skill://` resource is not — that has to be fetched by an agent that may
  * never think to. And `get` reads the database on each invocation, so instead of a procedure that
  * describes the steps in the abstract, this one names the step you are actually on.
  *
  * That is the whole design decision behind it. Onboarding is not a transaction: every tool writes
  * on the spot and a location half-finished is not invalid, only unfinished. So rather than protect
  * against stopping in the middle, this makes stopping in the middle cheap to recover from.
  */
object Prompts:

  private def message(text: String): PromptMessage = PromptMessage(
    role = Role.User,
    content = ToolContent.text(text))

  private[mcp] def render(r: Readiness.LocationReadiness): String =
    val locales =
      if r.locales.isEmpty then
        "none"
      else
        r.locales
          .map(g => s"${g.locale} (${g.missing} of ${r.requiredKeys} keys missing)")
          .mkString(", ")
    val steps =
      if r.nextSteps.isEmpty then
        "Nothing here blocks a deploy."
      else
        r.nextSteps.zipWithIndex.map((s, i) => s"${i + 1}. $s").mkString("\n")
    s"""Onboarding '${r.slug}' (${r.name}).
       |
       |Where it stands:
       |- active: ${r.active}
       |- translations: $locales
       |- stylesheet of its own: ${r.hasStyles}; brand icon of its own: ${r.hasIcon}
       |- videos: ${r.activeVideos} in rotation, ${r.pendingVideos} waiting for their file
       |- advertiser surveys here: ${r.advertiserSurveys}
       |- advertisers registered (deployment-wide): ${r.advertisers}
       |- UniFi configured: ${r.unifiConfigured}
       |
       |What is left:
       |$steps
       |
       |Work through these in order, asking the operator for anything only they can supply — the
       |wording of a question, the label of an option, the file for a video. Do not invent copy.
       |Each tool writes immediately; there is no commit step and no rollback, so stopping part way
       |is fine and this prompt will say where you stopped. Re-run it after a few steps to re-read
       |the state rather than tracking it yourself.
       |
       |Nothing here deploys. Publishing the location to AWS is still `captal locations push`."""
      .stripMargin
  end render

  private def catalogue(slugs: List[String]): String =
    if slugs.isEmpty then
      """No locations exist yet. Call create_location first — the slug is permanent, kebab-case and
        |at most 25 characters, because it becomes the URL prefix, the S3 prefix and the AWS resource
        |names. Then re-run this prompt with that slug.""".stripMargin
    else
      s"""Which location? Re-run this prompt with one of: ${slugs.mkString(", ")}.
         |
         |Or call create_location for a new one — the slug is permanent, kebab-case and at most 25
         |characters, because it becomes the URL prefix, the S3 prefix and the AWS resource names."""
        .stripMargin

  /** Takes the services rather than reaching for them: `McpPromptHandler.get` returns
    * `ZIO[Any, ...]`, so a prompt has no environment to ask for a database.
    */
  def onboardLocation(env: ZEnvironment[QuillSqlite & AssetStore]): McpPromptHandler = McpPrompt(
    "onboard-location")
    .description(
      "Walk a location from empty to ready: translations, advertisers, videos and the questions that follow them. Reads the current state and says which step is next, so it is worth re-running part way through.")
    .argument("slug", "Which location. Omit to list the ones that exist.", required = false)
    .get: args =>
      args.get("slug").map(_.trim).filter(_.nonEmpty) match
        case Some(slug) =>
          Readiness
            .of(slug)
            .map(r => PromptGetResult(messages = Chunk(message(render(r)))))
            .mapError(ToolError.apply)
            .provideEnvironment(env)
        case None =>
          LocationTools
            .slugs
            .map(slugs => PromptGetResult(messages = Chunk(message(catalogue(slugs)))))
            .provideEnvironment(env)

  def all(env: ZEnvironment[QuillSqlite & AssetStore]): List[McpPromptHandler] = List(
    onboardLocation(env))
end Prompts
