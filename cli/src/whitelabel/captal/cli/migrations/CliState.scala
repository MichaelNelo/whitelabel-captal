package whitelabel.captal.cli.migrations

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import io.circe.{Decoder, Encoder}
import io.circe.yaml.parser as yamlParser
import zio.*

/** Per-project state at `.captal/state.json`. Drives the schema-migration warning hook in
  * [[Main]]:
  *
  *   - `version` records the CLI version that last wrote this file. When the running CLI's
  *     version differs, [[Main.run]] enters the **first-run post-update** path (full project scan
  *     + interactive prompt).
  *   - `pendingFiles` caches the YAML paths that had conflicts at the time the operator declined
  *     the prompt. Subsequent invocations re-scan ONLY these paths (cheap; sub-millisecond) and
  *     remove any that have been fixed manually. When the list empties, [[clear]] removes the
  *     state file entirely (self-cleanup).
  */
final case class CliState(version: SemVer, pendingFiles: List[String])

object CliState:
  given Decoder[SemVer] = Decoder.decodeString.emap(SemVer.parse)
  given Encoder[SemVer] = Encoder.encodeString.contramap(_.toString)
  given Decoder[CliState] = Decoder.forProduct2("version", "pendingFiles")(CliState.apply)
  given Encoder[CliState] =
    Encoder.forProduct2("version", "pendingFiles")(s => (s.version, s.pendingFiles))

  /** Default for missing-or-corrupt state file. SemVer.zero forces the first-run path, which is
    * the right default: an operator without state.json is either (a) just installed the CLI for
    * the first time on an existing project (legacy YAMLs likely need migration) or (b) cleared
    * the file intentionally to re-trigger the check.
    */
  val default: CliState = CliState(SemVer.zero, Nil)

  private val Path: Path = Paths.get(".captal/state.json")

  /** Load state. Missing or parse-failure → [[default]]. */
  val load: UIO[CliState] = ZIO
    .attemptBlocking:
      if Files.exists(Path) then
        val text = new String(Files.readAllBytes(Path), StandardCharsets.UTF_8)
        // YAML is a superset of JSON; the yaml parser handles .captal/state.json fine.
        yamlParser.parse(text).flatMap(_.as[CliState]).getOrElse(default)
      else default
    .orElseSucceed(default)

  /** Persist state, creating `.captal/` if needed. Best-effort — IO failures are swallowed (the
    * worst that happens is the warning re-fires next invocation).
    */
  def save(state: CliState): UIO[Unit] = ZIO
    .attemptBlocking:
      val parent = Path.getParent
      if parent != null && !Files.exists(parent) then Files.createDirectories(parent)
      val json = summon[Encoder[CliState]].apply(state)
      Files.write(Path, json.spaces2.getBytes(StandardCharsets.UTF_8))
      ()
    .ignore

  /** Delete `.captal/state.json` entirely. Called by [[MigrateCommand]] on success and by the
    * post-command hook when `pendingFiles` empties (self-cleanup). Subsequent invocations see no
    * state file → fall back to [[default]] → first-run path → full scan finds no conflicts →
    * state is re-saved with empty pendingFiles. No infinite loop.
    */
  val clear: UIO[Unit] = ZIO
    .attemptBlocking:
      if Files.exists(Path) then Files.delete(Path)
      ()
    .ignore
end CliState
