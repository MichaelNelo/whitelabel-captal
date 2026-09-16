package whitelabel.captal.mcp

import com.jamesward.ziohttp.mcp.*
import com.jamesward.ziohttp.mcp.auth.McpAuth
import whitelabel.captal.core.i18n.{I18n, I18nSchema}
import whitelabel.captal.infra.schema.QuillSqlite
import zio.{Chunk, ZIO}

/** The read-only half of the backoffice server: everything a host can discover and read before any
  * tool exists to mutate anything.
  *
  * Skills are served as Resources under the `skill://` convention rather than through the
  * experimental `skills/list` and `skills/get` methods, because `McpDispatchMethod` is a closed
  * enum in the SDK — new JSON-RPC methods cannot be registered without patching the library, while
  * Resources are a first-class primitive that every host already understands.
  */
object CaptalMcp:
  val RequiredI18nKeysUri: String = "i18n://required-keys"

  /** Loaded into every conversation before any tool schema, so it stays an index and not a manual.
    *
    * Deliberately carries only URI and name. Each skill's description already travels on its
    * `ResourceDefinition`, where a host reads it via `resources/list` on demand; repeating it here
    * would be duplication paid on every single conversation.
    */
  val instructions: String =
    // The frontmatter name matches the slug for every skill today, so printing it is the URI
    // repeated back. Kept conditional rather than dropped, since frontmatter is data and a future
    // skill may well diverge.
    val catalogue = Skills
      .all
      .map: s =>
        val label =
          if s.name == s.slug then
            ""
          else
            s" (${s.name})"
        s"- `${s.uri}`$label"
      .mkString("\n")
    s"""Backoffice for the Captal whitelabel captive portal.
       |
       |Translations are edited here. Everything else — deployments, videos, surveys, advertisers —
       |still goes through the CLI.
       |
       |Read the relevant `skill://` resource before acting on a request in its area. Each one is a
       |procedure, not background reading, and they encode constraints that are not visible from
       |the data alone. Call `resources/list` for what each one covers.
       |
       |Skills:
       |$catalogue
       |
       |Other resources:
       |- `$RequiredI18nKeysUri` — every translation key the portal requires, with what each one is
       |  for. A locale missing any of these renders the literal key to end users.
       |""".stripMargin
  end instructions

  private def skillResource(skill: Skill): McpResourceHandler = McpResource(skill.uri, skill.name)
    .description(skill.description)
    .mimeType("text/markdown")
    .read(uri => ZIO.succeed(Chunk(ResourceContents(uri, Some("text/markdown"), Some(skill.body)))))

  /** Key plus what it is for, both derived: the list from the type, the descriptions from an
    * [[I18n]] instance the compiler forces to be total. This replaced the hand-written enumeration
    * in the `i18n-reference` skill, which had drifted and was missing two keys.
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

  /** Auth is not optional.
    *
    * The SDK ignores `requireScopes` declarations when no `.auth(...)` is configured — silently, so
    * an unauthenticated server looks exactly like a scoped one from the outside while every tool is
    * open to anyone who can reach the port.
    */
  def server(
      auth: McpAuth[Any],
      name: String = "captal-backoffice",
      version: String = "0.1.0"): McpServer[QuillSqlite & AssetStore] =
    val withResources =
      resources.foldLeft(McpServer(name, version).instructions(instructions).auth(auth))(
        _.resource(_))
    val withData = (I18nTools.all ++ AdvertiserTools.all ++ SurveyTools.all).foldLeft[McpServer[
      QuillSqlite]](withResources)(_.tool(_))
    val withAssets = (AssetTools.all ++ VideoTools.all).foldLeft[McpServer[
      QuillSqlite & AssetStore]](withData)(_.tool(_))
    // The upload page is served per video by a source rather than as a static resource: it is built
    // from the row, a freshly signed URL and the location's stylesheet, none of which a static
    // handler can reach.
    withAssets.resourceSource(UploadUiSource())
end CaptalMcp
