package whitelabel.captal.mcp

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

import com.jamesward.ziohttp.mcp.*
import zio.*
import zio.json.ast.Json

/** The bundled skills over `skills/list` and `skills/get`, beside the `skill://` resources.
  *
  * Beside, not instead of. Three channels now carry the same twelve documents, and each exists
  * because the one before it is not guaranteed to arrive:
  *
  *   - `read_skill`, a tool. The only one a host cannot decline to show a model, which is why it
  *     stays even though the primitive now exists. Resources were the same bet and lost it: in the
  *     host these operators use they never reach the model at all.
  *   - `skill://…` resources, which an operator can attach by hand, and which are also where the
  *     *content* comes from for this extension — `skills/get` answers with metadata and a digest,
  *     and the client reads the body through core `resources/read`.
  *   - this, for a host that implements the extension and can then discover and cache them without
  *     being told to.
  *
  * None of it is new capability; it is the same text reachable three ways. The reason to add the
  * third is that it is the one a host can act on without an operator in the loop.
  *
  * What made this impossible before was real and is now gone: `skills/list` and `skills/get` are
  * JSON-RPC methods the SDK had no way to register, so the convention of serving skills as
  * resources was the only option rather than a preference. 0.10.0 made extensions a registry of
  * operations, and [[McpSkills]] is one.
  */
object SkillsExtension:

  /** A skill the extension cannot advertise, and why.
    *
    * Reported rather than fatal, which is how the SDK's own loader behaves. The failure mode here
    * is a document, and losing a server because one of them has a malformed frontmatter line would
    * be worse than losing its entry in one of three listings — it is still reachable through
    * `read_skill` either way.
    */
  final case class Skipped(slug: String, reason: String)

  private def digestOf(body: String): Either[McpSkillDigestError, McpSkillDigest] =
    val bytes = MessageDigest.getInstance("SHA-256").digest(body.getBytes(UTF_8))
    McpSkillDigest.parse(s"sha256:${bytes.map(byte => f"${byte & 0xff}%02x").mkString}")

  /** One skill as the extension's own entry.
    *
    * `static` rather than `dynamic`: these ship in the jar, so the resource list is known and
    * fixed, and the digest lets a client cache a skill and tell whether its copy is current. A
    * dynamic entry would carry no resource list and make every read unconditional.
    *
    * The digest covers [[Skill.body]], which is what `resources/read` returns for this URI — the
    * frontmatter is stripped on load and travels as metadata instead. Digesting the file on disk
    * would be digesting something no one can fetch.
    */
  private def entryOf(skill: Skill): Either[String, McpSkillEntry] =
    for
      uri         <- McpSkillUri.parse(skill.uri).left.map(error => s"not a skill URI ($error)")
      resourceUri <- McpSkillResourceUri
        .parse(skill.uri)
        .left
        .map(error => s"not a resource URI ($error)")
      digest <- digestOf(skill.body).left.map(error => s"could not be digested ($error)")
      size   <- McpSkillSize
        .parse(skill.body.getBytes(UTF_8).length.toLong)
        .left
        .map(error => s"has no usable size ($error)")
      // Only the two keys the contract reads, and the two [[Skills]] parses. The frontmatter in the
      // file may carry more; passing what we did not parse would mean claiming to have read it.
      frontmatter = McpSkillFrontmatter(
        Json.Obj("name" -> Json.Str(skill.name), "description" -> Json.Str(skill.description)))
      entry <- McpSkillEntry
        .static(uri, frontmatter, NonEmptyChunk(McpSkillResource(resourceUri, digest, size)))
        .left
        // `UriNameMismatch` is the one to expect: the contract requires the frontmatter `name` to
        // equal the directory it lives in, and `Skills.load` falls back to the slug when the line
        // is missing, so a misnamed skill reaches here looking almost right.
        .map(error => s"was rejected by the Skills contract ($error)")
    yield entry

  private lazy val resolved: List[Either[Skipped, McpSkillEntry]] = Skills
    .all
    .map(skill => entryOf(skill).left.map(reason => Skipped(skill.slug, reason)))

  lazy val entries: Chunk[McpSkillEntry] = Chunk.fromIterable(
    resolved.collect { case Right(entry) =>
      entry
    })

  lazy val skipped: List[Skipped] = resolved.collect { case Left(skip) =>
    skip
  }

  /** Logged at startup because the alternative is silence: a skill missing from `skills/list` looks
    * exactly like a skill that was never written.
    */
  val logSkipped: UIO[Unit] =
    ZIO.foreachDiscard(skipped): skip =>
      ZIO.logWarning(s"Skill '${skip.slug}' is not published over skills/list: it ${skip.reason}")

  private[mcp] val source: McpSkillsSource[Any] =
    new McpSkillsSource[Any]:
      /** Unpaginated, and a cursor is an error rather than a restart.
        *
        * Twelve entries of metadata fit in one response with room to spare. Accepting a cursor we
        * do not issue and answering from the top would look like progress and loop forever.
        */
      def list(
          params: McpSkillsListParams,
          ctx: McpRequestContext): IO[McpSkillsSourceError, McpSkillsListResult] =
        params.cursor match
          case None =>
            ZIO.succeed(McpSkillsListResult(entries))
          case Some(cursor) =>
            ZIO.fail(
              McpSkillsSourceError.InvalidParams(
                s"skills/list is not paginated, so '$cursor' cannot have come from it."))

      def get(uri: McpSkillUri, ctx: McpRequestContext): IO[McpSkillsSourceError, McpSkillEntry] =
        ZIO
          .fromOption(entries.find(_.uri.value == uri.value))
          .orElseFail(
            McpSkillsSourceError.InvalidParams(
              s"No skill at '${uri
                  .value}'. Call skills/list, or read_skill for the same set as a tool."))

  /** Registered without the directory half.
    *
    * `resources/directory/read` browses the files beside a `SKILL.md`, and every skill here is a
    * single document — the listing it would return is the entry itself. Declaring
    * `directoryRead: true` and answering with one known file is a capability claim with nothing
    * behind it.
    */
  val extension: McpExtensions[Any] = McpSkills(source)
end SkillsExtension
