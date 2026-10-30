package whitelabel.captal.platform.repositories

import java.time.Instant

import io.getquill.*
import whitelabel.captal.core.infrastructure.RecoveryRepository
import whitelabel.captal.core.recovery.{
  Activity,
  ErrorFingerprint,
  ErrorId,
  ErrorRecord,
  Manifest,
  Origin,
  Signature
}
import whitelabel.captal.core.user
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.core.given
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.{
  AnswerRow,
  BundleManifest,
  ErrorRow,
  Errors,
  QuestionRow,
  SurveyCategory,
  SurveyRow,
  VideoFiles,
  VideoViewRow
}
import zio.*

object RecoveryRepositoryQuill:
  def apply(quill: QuillSqlite, bundle: BundleManifest): RecoveryRepository[Task] =
    new RecoveryRepository[Task]:
      import quill.*

      def find(errorId: ErrorId): Task[Option[ErrorRecord]] =
        run(query[ErrorRow].filter(_.id == lift(errorId.asString)))
          .map(_.headOption.flatMap(toRecord))
          .orDie

      def fingerprintOf(signature: Signature): Task[ErrorFingerprint] = ZIO.succeed(
        Errors.fingerprint(signature))

      def publishedBundle(): Task[Option[Manifest]] = bundle.current

      /** Read by fingerprint and narrowed here: the rows of one error are few, and the stamps
        * compare as text.
        */
      def devicesReporting(
          fp: ErrorFingerprint,
          excluding: user.DeviceId,
          since: Instant): Task[Int] =
        val from = VideoFiles.stamp(since)
        run(query[ErrorRow].filter(_.fingerprint.contains(lift(fp.value))))
          .map(
            _.filter(_.createdAt >= from)
              .flatMap(_.deviceId)
              .filterNot(_ == excluding)
              .distinct
              .size)
          .orDie

      def recoveriesSince(deviceId: user.DeviceId, since: Instant): Task[Int] =
        val from = VideoFiles.stamp(since)
        run(
          query[ErrorRow].filter(e =>
            e.deviceId.contains(lift(deviceId)) && e.redeemedAt.isDefined))
          .map(_.count(_.redeemedAt.exists(_ >= from)))
          .orDie

      /** Answers by the category of their survey: one row per answer, and a user's answers on one
        * device are few.
        */
      def activity(userId: user.Id, deviceId: user.DeviceId): Task[Activity] =
        val program =
          for
            categories <- run(
              for
                a <- query[AnswerRow].filter(a =>
                  a.userId == lift(userId) && a.deviceId.contains(lift(deviceId)))
                q <- query[QuestionRow].join(_.id == a.questionId)
                s <- query[SurveyRow].join(_.id == q.surveyId)
              yield s.category)
            views <- run(
              query[VideoViewRow]
                .filter(v => v.userId.contains(lift(userId)) && v.deviceId.contains(lift(deviceId)))
                .size)
          yield Activity(
            videosWatched = views.toInt,
            videoAnswers = categories.count(_ == SurveyCategory.Advertiser),
            identificationAnswers = categories.count(c =>
              c == SurveyCategory.Profiling || c == SurveyCategory.Location),
            emailAnswers = categories.count(_ == SurveyCategory.Email)
          )
        program.orDie
      end activity

  private def toRecord(row: ErrorRow): Option[ErrorRecord] = ErrorId
    .fromString(row.id)
    .map: id =>
      ErrorRecord(
        errorId = id,
        sessionId = row.sessionId,
        origin =
          if row.origin == "server" then
            Origin.Server
          else
            Origin.Client
        ,
        recordedAt = Instant.parse(row.createdAt),
        fingerprint = row.fingerprint.map(ErrorFingerprint(_)),
        verdict = row.verdict.flatMap(Errors.verdictOf),
        availableAt = row.availableAt.map(Instant.parse),
        redeemedAt = row.redeemedAt.map(Instant.parse)
      )

  val layer: ZLayer[QuillSqlite & BundleManifest, Nothing, RecoveryRepository[Task]] = ZLayer
    .fromFunction(apply)
end RecoveryRepositoryQuill
