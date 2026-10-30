package whitelabel.captal.platform

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

import io.circe.Json
import io.getquill.*
import whitelabel.captal.core.infrastructure.SessionData
import whitelabel.captal.core.recovery.{
  ClientReport,
  ErrorFingerprint,
  ErrorId,
  Reason,
  Signature,
  Verdict
}
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.core.given
import whitelabel.captal.platform.schema.given
import zio.*

/** The `errors` table: what was recorded, and in which words. */
object Errors:

  /** Records a defect the API caught, against the session it happened in when there is one, and
    * answers the id the page can leave through. Never fails: an error that cannot be recorded is
    * still answered, only without an id.
    */
  def recordServer(
      quill: QuillSqlite,
      session: Option[SessionData],
      message: String,
      detail: String,
      at: Instant): UIO[Option[ErrorId]] =
    import quill.*
    val errorId = ErrorId.generate
    val row = ErrorRow(
      id = errorId.asString,
      sessionId = session.map(_.sessionId),
      deviceId = session.map(_.deviceId),
      origin = "server",
      message = message,
      detail = detail,
      fingerprint = None,
      verdict = None,
      createdAt = VideoFiles.stamp(at),
      availableAt = None,
      redeemedAt = None
    )
    run(query[ErrorRow].insertValue(lift(row)))
      .as(Option(errorId))
      .catchAll(e => ZIO.logWarning(s"Could not record a server error: ${e.getMessage}").as(None))
  end recordServer

  /** SHA-256 over the signature: two reports of the same error from different guests hash alike,
    * two different errors almost never do.
    */
  def fingerprint(signature: Signature): ErrorFingerprint =
    val canonical = List(
      signature.bundleVersion,
      signature.name,
      signature.file,
      signature.line.toString,
      signature.column.toString).mkString("\n")
    val digest = MessageDigest
      .getInstance("SHA-256")
      .digest(canonical.getBytes(StandardCharsets.UTF_8))
    ErrorFingerprint(digest.map(b => f"$b%02x").mkString)

  /** What the client sent beyond its message: the bundle version it runs, the error's name and its
    * frames.
    */
  def detail(report: ClientReport): String =
    Json
      .obj(
        "bundleVersion" -> Json.fromString(report.bundleVersion),
        "name"          -> Json.fromString(report.name),
        "frames"        ->
          Json.arr(
            report
              .frames
              .map(f =>
                Json.obj(
                  "file"   -> Json.fromString(f.file),
                  "line"   -> Json.fromInt(f.line),
                  "column" -> Json.fromInt(f.column)))*)
      )
      .noSpaces

  /** How the verdict is stored: `plausible`, `recurrent`, or `fabricated:` and its reasons. */
  def verdict(v: Verdict): String =
    v match
      case Verdict.Fabricated(reasons) =>
        "fabricated:" + reasons.map(_.toString).mkString(",")
      case Verdict.Plausible(true) =>
        "recurrent"
      case Verdict.Plausible(false) =>
        "plausible"

  /** [[verdict]], read back. A reason this version does not know is dropped, not the verdict. */
  def verdictOf(stored: String): Option[Verdict] =
    stored match
      case "plausible" =>
        Some(Verdict.Plausible(false))
      case "recurrent" =>
        Some(Verdict.Plausible(true))
      case fabricated if fabricated.startsWith("fabricated:") =>
        Some(
          Verdict.Fabricated(
            fabricated
              .stripPrefix("fabricated:")
              .split(",")
              .toList
              .flatMap(name => Reason.values.find(_.toString == name))))
      case _ =>
        None
end Errors
