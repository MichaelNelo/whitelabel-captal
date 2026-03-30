package whitelabel.captal.infra.provision

import io.circe.syntax.*
import io.getquill.*
import whitelabel.captal.infra.*
import whitelabel.captal.infra.schema.QuillSqlite
import whitelabel.captal.infra.schema.core.given
import whitelabel.captal.infra.schema.given
import zio.*

/** Writes provisioned entities to the database using Quill. */
object EntityWriter:

  def upsertLocation(quill: QuillSqlite)(
      id: String,
      slug: String,
      name: String): Task[Unit] =
    import quill.*
    val now = java.time.Instant.now.toString
    val row = LocationRow(id, slug, name, 1, now, now)
    run(
      query[LocationRow]
        .insertValue(lift(row))
        .onConflictUpdate(_.id)(
          (t, e) => t.name -> e.name,
          (t, _) => t.isActive -> lift(1),
          (t, _) => t.updatedAt -> lift(now))).unit

  def upsertAdvertiser(quill: QuillSqlite)(
      id: String,
      name: String,
      priority: Int): Task[Unit] =
    import quill.*
    val now = java.time.Instant.now.toString
    val row = AdvertiserRow(id, name, priority, 1, now, now)
    run(
      query[AdvertiserRow]
        .insertValue(lift(row))
        .onConflictUpdate(_.id)(
          (t, e) => t.name -> e.name,
          (t, e) => t.priority -> e.priority,
          (t, _) => t.isActive -> lift(1),
          (t, _) => t.updatedAt -> lift(now))).unit

  def upsertVideo(quill: QuillSqlite)(
      id: String,
      advertiserId: Option[String],
      videoType: String,
      videoUrl: String,
      durationSeconds: Int,
      minWatchSeconds: Int,
      showCountdown: Int,
      noRepeatSeconds: Option[Int],
      locationId: Option[String],
      priority: Int): Task[Unit] =
    import quill.*
    val now = java.time.Instant.now.toString
    val row = AdvertiserVideoRow(
      whitelabel.captal.core.video.Id.unsafe(id),
      advertiserId,
      videoType,
      videoUrl,
      durationSeconds,
      minWatchSeconds,
      showCountdown,
      noRepeatSeconds,
      locationId,
      1,
      priority,
      now,
      now)
    run(
      query[AdvertiserVideoRow]
        .insertValue(lift(row))
        .onConflictUpdate(_.id)(
          (t, e) => t.videoUrl -> e.videoUrl,
          (t, e) => t.durationSeconds -> e.durationSeconds,
          (t, e) => t.minWatchSeconds -> e.minWatchSeconds,
          (t, e) => t.showCountdown -> e.showCountdown,
          (t, e) => t.priority -> e.priority,
          (t, _) => t.isActive -> lift(1),
          (t, _) => t.updatedAt -> lift(now))).unit

  def upsertSurvey(quill: QuillSqlite)(
      id: String,
      category: String,
      advertiserId: Option[String],
      videoId: Option[String],
      locationId: Option[String]): Task[Unit] =
    import quill.*
    val now = java.time.Instant.now.toString
    val row = SurveyRow(
      whitelabel.captal.core.survey.Id.unsafe(id),
      category,
      advertiserId,
      videoId,
      locationId,
      1,
      now)
    run(
      query[SurveyRow]
        .insertValue(lift(row))
        .onConflictUpdate(_.id)(
          (t, _) => t.isActive -> lift(1))).unit

  def upsertQuestion(quill: QuillSqlite)(
      id: String,
      surveyId: String,
      questionType: String,
      pointsAwarded: Int,
      displayOrder: Int,
      hierarchyLevel: Option[String],
      isRequired: Int): Task[Unit] =
    import quill.*
    val now = java.time.Instant.now.toString
    val row = QuestionRow(
      whitelabel.captal.core.survey.question.Id.unsafe(id),
      whitelabel.captal.core.survey.Id.unsafe(surveyId),
      questionType,
      pointsAwarded,
      displayOrder,
      hierarchyLevel,
      isRequired,
      now)
    run(
      query[QuestionRow]
        .insertValue(lift(row))
        .onConflictUpdate(_.id)(
          (t, e) => t.questionType -> e.questionType,
          (t, e) => t.pointsAwarded -> e.pointsAwarded,
          (t, e) => t.displayOrder -> e.displayOrder)).unit

  def upsertQuestionOption(quill: QuillSqlite)(
      id: String,
      questionId: String,
      displayOrder: Int): Task[Unit] =
    import quill.*
    val row = QuestionOptionRow(
      whitelabel.captal.core.survey.question.OptionId.unsafe(id),
      whitelabel.captal.core.survey.question.Id.unsafe(questionId),
      displayOrder,
      None)
    run(
      query[QuestionOptionRow]
        .insertValue(lift(row))
        .onConflictUpdate(_.id)(
          (t, e) => t.displayOrder -> e.displayOrder)).unit

  def upsertQuestionRule(quill: QuillSqlite)(
      id: String,
      questionId: String,
      ruleType: String,
      ruleConfig: String): Task[Unit] =
    import quill.*
    val row = QuestionRuleRow(
      id,
      whitelabel.captal.core.survey.question.Id.unsafe(questionId),
      ruleType,
      ruleConfig)
    run(
      query[QuestionRuleRow]
        .insertValue(lift(row))
        .onConflictUpdate(_.id)(
          (t, e) => t.ruleType -> e.ruleType,
          (t, e) => t.ruleConfig -> e.ruleConfig)).unit

  def upsertLocalizedText(quill: QuillSqlite)(
      id: String,
      entityId: String,
      locale: String,
      value: String,
      category: String = "backend"): Task[Unit] =
    import quill.*
    val now = java.time.Instant.now.toString
    val row = LocalizedTextRow(id, entityId, locale, value, category, now, now)
    run(
      query[LocalizedTextRow]
        .insertValue(lift(row))
        .onConflictUpdate(_.id)(
          (t, e) => t.value -> e.value,
          (t, _) => t.updatedAt -> lift(now))).unit

  /** Soft-delete a video by setting is_active = 0 */
  def deactivateVideo(quill: QuillSqlite)(id: String): Task[Unit] =
    import quill.*
    val now = java.time.Instant.now.toString
    val videoId = whitelabel.captal.core.video.Id.unsafe(id)
    run(
      query[AdvertiserVideoRow]
        .filter(_.id == lift(videoId))
        .update(_.isActive -> 0, _.updatedAt -> lift(now))).unit

  /** Soft-delete a survey by setting is_active = 0 */
  def deactivateSurvey(quill: QuillSqlite)(id: String): Task[Unit] =
    import quill.*
    val surveyId = whitelabel.captal.core.survey.Id.unsafe(id)
    run(
      query[SurveyRow]
        .filter(_.id == lift(surveyId))
        .update(_.isActive -> 0)).unit

  /** Soft-delete an advertiser by setting is_active = 0 */
  def deactivateAdvertiser(quill: QuillSqlite)(id: String): Task[Unit] =
    import quill.*
    val now = java.time.Instant.now.toString
    run(
      query[AdvertiserRow]
        .filter(_.id == lift(id))
        .update(_.isActive -> 0, _.updatedAt -> lift(now))).unit

  /** Upsert a provision manifest entry */
  def upsertManifest(quill: QuillSqlite)(
      entityKey: String,
      locationId: Option[String],
      contentHash: String): Task[Unit] =
    import quill.*
    val now = java.time.Instant.now.toString
    val row = ProvisionManifestRow(entityKey, locationId, contentHash, now)
    run(
      query[ProvisionManifestRow]
        .insertValue(lift(row))
        .onConflictUpdate(_.entityKey)(
          (t, e) => t.contentHash -> e.contentHash,
          (t, _) => t.provisionedAt -> lift(now))).unit

  /** Remove a provision manifest entry */
  def deleteManifest(quill: QuillSqlite)(entityKey: String): Task[Unit] =
    import quill.*
    run(query[ProvisionManifestRow].filter(_.entityKey == lift(entityKey)).delete).unit

  /** Load all manifest entries */
  def loadManifest(quill: QuillSqlite): Task[Map[String, String]] =
    import quill.*
    run(query[ProvisionManifestRow]).map(_.map(r => r.entityKey -> r.contentHash).toMap)

  /** Upsert i18n translations as localized_texts with category=frontend */
  def upsertI18n(quill: QuillSqlite)(locale: String, translations: Map[String, String]): Task[Unit] =
    ZIO.foreachDiscard(translations.toList): (key, value) =>
      val id = IdGenerator.localizedTextId(s"i18n.$key", locale)
      upsertLocalizedText(quill)(id, s"i18n.$key", locale, value, "frontend")
