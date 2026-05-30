package whitelabel.captal.cli.migrations

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import io.circe.{Decoder, Encoder, Json}
import io.circe.yaml.parser as yamlParser
import zio.*

/** Per-project state file at `.captal/state.json`. Tracks the last CLI version the operator saw,
  * so the after-command warning hook only fires for migrations introduced AFTER the previous
  * invocation.
  */
final case class CliState(lastSeenCliVersion: SemVer)

object CliState:
  given Decoder[SemVer] = Decoder.decodeString.emap(SemVer.parse)
  given Encoder[SemVer] = Encoder.encodeString.contramap(_.toString)
  given Decoder[CliState] = Decoder.forProduct1("lastSeenCliVersion")(CliState.apply)
  given Encoder[CliState] = Encoder.forProduct1("lastSeenCliVersion")(_.lastSeenCliVersion)

  private val StatePath: Path = Paths.get(".captal/state.json")

  /** Load state from `.captal/state.json`. Missing file → `CliState(SemVer.zero)` (legacy install
    * — all migrations show as pending). Parse failures also fall back to zero rather than abort
    * the command; the state file is convenience, not correctness-critical.
    */
  val load: UIO[CliState] = ZIO
    .attemptBlocking:
      if Files.exists(StatePath) then
        val text = new String(Files.readAllBytes(StatePath), StandardCharsets.UTF_8)
        // YAML is a superset of JSON, so the yaml parser handles `.captal/state.json` too.
        yamlParser
          .parse(text)
          .flatMap(_.as[CliState])
          .getOrElse(CliState(SemVer.zero))
      else CliState(SemVer.zero)
    .orElseSucceed(CliState(SemVer.zero))

  /** Persist state, creating `.captal/` if needed. Best-effort — IO failures are swallowed (the
    * worst that happens is the warning re-fires next invocation).
    */
  def save(state: CliState): UIO[Unit] = ZIO
    .attemptBlocking:
      val parent = StatePath.getParent
      if parent != null && !Files.exists(parent) then Files.createDirectories(parent)
      val json: Json = summon[Encoder[CliState]].apply(state)
      Files.write(StatePath, json.spaces2.getBytes(StandardCharsets.UTF_8))
      ()
    .ignore
end CliState
