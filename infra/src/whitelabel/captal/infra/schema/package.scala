package whitelabel.captal.infra

import io.getquill.*
import io.getquill.jdbczio.Quill

package object schema:
  type QuillSqlite = Quill.Sqlite[SnakeCase]

  // ─────────────────────────────────────────────────────────────────────────────
  // SQLite Infix Functions for Quill DSL
  // ─────────────────────────────────────────────────────────────────────────────

  inline def sqliteRandom: Quoted[Double] =
    sql"RANDOM()".as[Double]

  inline def sqliteAbs(inline x: Double): Quoted[Double] =
    sql"ABS($x)".as[Double]

  inline def sqliteLog(inline x: Double): Quoted[Double] =
    sql"LOG($x)".as[Double]

  inline def nullif(inline x: Double, inline y: Double): Quoted[Double] =
    sql"NULLIF($x, $y)".as[Double]

  inline def datetimeMinusSeconds(inline seconds: Int): Quoted[String] =
    sql"datetime('now', '-' || $seconds || ' seconds')".as[String]

  // Comparación de strings para Quill (evita augmentString que Quill no puede parsear)
  inline def strGt(inline a: String, inline b: String): Quoted[Boolean] =
    sql"$a > $b".as[Boolean]

  // ─────────────────────────────────────────────────────────────────────────────
  // Schema Meta - Row types
  // ─────────────────────────────────────────────────────────────────────────────

  inline given SchemaMeta[UserRow] = schemaMeta[UserRow]("users")
  inline given SchemaMeta[SessionRow] = schemaMeta[SessionRow]("sessions")
  inline given SchemaMeta[SurveyRow] = schemaMeta[SurveyRow]("surveys")
  inline given SchemaMeta[QuestionRow] = schemaMeta[QuestionRow]("questions")
  inline given SchemaMeta[QuestionOptionRow] = schemaMeta[QuestionOptionRow]("question_options")
  inline given SchemaMeta[QuestionRuleRow] = schemaMeta[QuestionRuleRow]("question_rules")
  inline given SchemaMeta[AnswerRow] = schemaMeta[AnswerRow]("answers")
  inline given SchemaMeta[UserSurveyProgressRow] = schemaMeta[UserSurveyProgressRow](
    "user_survey_progress")
  inline given SchemaMeta[LocalizedTextRow] = schemaMeta[LocalizedTextRow]("localized_texts")
  inline given SchemaMeta[AdvertiserRow] = schemaMeta[AdvertiserRow]("advertisers")
  inline given SchemaMeta[AdvertiserVideoRow] = schemaMeta[AdvertiserVideoRow]("advertiser_videos")
  inline given SchemaMeta[VideoViewRow] = schemaMeta[VideoViewRow]("video_views")
end schema
