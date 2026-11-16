package whitelabel.captal.platform

import java.time.Duration

import io.getquill.*
import whitelabel.captal.core.recovery.{PolicyOverrides, WaitPolicy}
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import zio.*

/** A location's own recovery settings: the [[WaitPolicy]] its portal offers a way out of a client
  * error with, and the grace its playbacks get. Read by the portal on every request for a way out
  * and by the backoffice for every playback it times, so a change applies to the next one with no
  * redeploy.
  *
  * A location keeps a row only for what it changed. Whatever the row leaves out is the default, as
  * `core`'s [[PolicyOverrides]] fills it in.
  */
object LocationRecovery:

  inline def byLocationQuery = quote: (locationIdParam: String) =>
    query[LocationRecoveryRow].filter(_.locationId == locationIdParam).take(1)

  /** What the row sets, the rest left to the defaults; nothing, without a row. */
  def overridesOf(row: Option[LocationRecoveryRow]): PolicyOverrides =
    row.fold(PolicyOverrides()): r =>
      PolicyOverrides(
        maximum = r.maximumSeconds.map(seconds),
        ceiling = r.ceilingSeconds.map(seconds),
        zeroBelow = r.zeroBelowSeconds.map(seconds),
        videoWeight = r.videoWeight,
        videoAnswerWeight = r.videoAnswerWeight,
        identificationWeight = r.identificationWeight,
        emailWeight = r.emailWeight,
        identifiedFactor = r.identifiedFactor,
        watchedFactor = r.watchedFactor,
        recoveryExponent = r.recoveryExponent,
        fabricatedFactor = r.fabricatedFactor,
        recurrentFactor = r.recurrentFactor,
        recoveryWindow = r.recoveryWindowSeconds.map(seconds),
        recurrenceWindow = r.recurrenceWindowSeconds.map(seconds),
        playbackGrace = r.playbackGraceSeconds.map(seconds)
      )

  /** The row that holds `overrides` for `locationId`, as of `updatedAt`. */
  def rowOf(
      locationId: String,
      overrides: PolicyOverrides,
      updatedAt: String): LocationRecoveryRow =
    def stored(length: Option[Duration]) = length.map(_.getSeconds.toInt)
    LocationRecoveryRow(
      locationId = locationId,
      maximumSeconds = stored(overrides.maximum),
      ceilingSeconds = stored(overrides.ceiling),
      zeroBelowSeconds = stored(overrides.zeroBelow),
      videoWeight = overrides.videoWeight,
      videoAnswerWeight = overrides.videoAnswerWeight,
      identificationWeight = overrides.identificationWeight,
      emailWeight = overrides.emailWeight,
      identifiedFactor = overrides.identifiedFactor,
      watchedFactor = overrides.watchedFactor,
      recoveryExponent = overrides.recoveryExponent,
      fabricatedFactor = overrides.fabricatedFactor,
      recurrentFactor = overrides.recurrentFactor,
      recoveryWindowSeconds = stored(overrides.recoveryWindow),
      recurrenceWindowSeconds = stored(overrides.recurrenceWindow),
      playbackGraceSeconds = stored(overrides.playbackGrace),
      updatedAt = updatedAt
    )
  end rowOf

  def waitPolicy(row: Option[LocationRecoveryRow]): WaitPolicy = overridesOf(row).policy

  def grace(row: Option[LocationRecoveryRow]): Duration = overridesOf(row).grace

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
