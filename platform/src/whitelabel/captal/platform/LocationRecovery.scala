package whitelabel.captal.platform

import java.time.Duration

import io.getquill.*
import whitelabel.captal.core.recovery.WaitPolicy
import whitelabel.captal.core.video.Playback
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import zio.*

/** A location's own recovery settings: the [[WaitPolicy]] its portal offers a way out of a client
  * error with, and the grace its playbacks get. Read by the portal on every request for a way out
  * and by the backoffice for every playback it times, so a change applies to the next one with no
  * redeploy.
  *
  * A location keeps a row only for what it changed. Whatever the row leaves out is
  * [[WaitPolicy.Default]] and [[Playback.Grace]].
  */
object LocationRecovery:

  inline def byLocationQuery = quote: (locationIdParam: String) =>
    query[LocationRecoveryRow].filter(_.locationId == locationIdParam).take(1)

  def waitPolicy(row: Option[LocationRecoveryRow]): WaitPolicy =
    val default = WaitPolicy.Default
    row.fold(default): r =>
      WaitPolicy(
        maximum = r.maximumSeconds.fold(default.maximum)(seconds),
        ceiling = r.ceilingSeconds.fold(default.ceiling)(seconds),
        zeroBelow = r.zeroBelowSeconds.fold(default.zeroBelow)(seconds),
        videoWeight = r.videoWeight.getOrElse(default.videoWeight),
        videoAnswerWeight = r.videoAnswerWeight.getOrElse(default.videoAnswerWeight),
        identificationWeight = r.identificationWeight.getOrElse(default.identificationWeight),
        emailWeight = r.emailWeight.getOrElse(default.emailWeight),
        identifiedFactor = r.identifiedFactor.getOrElse(default.identifiedFactor),
        watchedFactor = r.watchedFactor.getOrElse(default.watchedFactor),
        recoveryExponent = r.recoveryExponent.getOrElse(default.recoveryExponent),
        fabricatedFactor = r.fabricatedFactor.getOrElse(default.fabricatedFactor),
        recurrentFactor = r.recurrentFactor.getOrElse(default.recurrentFactor),
        recoveryWindow = r.recoveryWindowSeconds.fold(default.recoveryWindow)(seconds),
        recurrenceWindow = r.recurrenceWindowSeconds.fold(default.recurrenceWindow)(seconds)
      )

  def grace(row: Option[LocationRecoveryRow]): Duration =
    row.flatMap(_.playbackGraceSeconds).fold(Playback.Grace)(seconds)

  def find(quill: QuillSqlite, locationId: String): Task[Option[LocationRecoveryRow]] =
    import quill.*
    run(byLocationQuery(lift(locationId))).map(_.headOption)

  def waitPolicyOf(quill: QuillSqlite, locationId: String): Task[WaitPolicy] = find(
    quill,
    locationId).map(waitPolicy)

  def graceOf(quill: QuillSqlite, locationId: String): Task[Duration] = find(quill, locationId).map(
    grace)

  /** Writes the location's row whole, creating it the first time. Answers the rows affected. */
  def save(quill: QuillSqlite, row: LocationRecoveryRow): Task[Long] =
    import quill.*
    find(quill, row.locationId).flatMap:
      case Some(_) =>
        run(
          query[LocationRecoveryRow]
            .filter(_.locationId == lift(row.locationId))
            .updateValue(lift(row)))
      case None =>
        run(query[LocationRecoveryRow].insertValue(lift(row)))

  private def seconds(value: Int): Duration = Duration.ofSeconds(value.toLong)
end LocationRecovery
