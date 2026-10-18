package whitelabel.captal.platform

import io.getquill.*
import io.getquill.jdbczio.Quill

package object schema:
  type QuillSqlite = Quill.Sqlite[SnakeCase]

  // ─────────────────────────────────────────────────────────────────────────────
  // SQLite Infix Functions for Quill DSL
  // ─────────────────────────────────────────────────────────────────────────────

  inline def sqliteRandom: Quoted[Double] = sql"RANDOM()".as[Double]

  inline def sqliteAbs(inline x: Double): Quoted[Double] = sql"ABS($x)".as[Double]

  inline def sqliteLog(inline x: Double): Quoted[Double] = sql"LOG($x)".as[Double]

  inline def nullif(inline x: Double, inline y: Double): Quoted[Double] = sql"NULLIF($x, $y)".as[
    Double]

  inline def castAsReal(inline x: Int): Quoted[Double] = sql"CAST(($x) AS REAL)".as[Double]

  inline def castAsRealOpt(inline x: Option[Int]): Quoted[Double] = sql"CAST(($x) AS REAL)".as[
    Double]

  inline def datetimeMinusSeconds(inline seconds: Int): Quoted[String] =
    sql"datetime('now', '-' || $seconds || ' seconds')".as[String]

  // String comparison for Quill, avoiding the augmentString it cannot parse.
  inline def strGt(inline a: String, inline b: String): Quoted[Boolean] = sql"$a > $b".as[Boolean]

  // ─────────────────────────────────────────────────────────────────────────────
  // Schema Meta - Row types
  // ─────────────────────────────────────────────────────────────────────────────

  inline given SchemaMeta[UserRow] = schemaMeta[UserRow]("users")
  inline given SchemaMeta[SessionRow] = schemaMeta[SessionRow]("sessions")

  /** Missing until now, and every device write has been going nowhere.
    *
    * Without it Quill derives the table name from the class and asks for `device_row`, which does
    * not exist — V6 created `devices`. `SessionService` writes one of these on every session, so
    * that insert has been failing since V6 and `devices` is empty wherever this has run.
    *
    * Nothing said so because the old rqlite JDBC driver reported a refused statement as zero
    * affected rows inside an HTTP 200: "no such table" arrived as "nothing happened". Driver
    * 10.5.2.1 throws, and the session endpoint started answering 500 — which is how this was
    * finally found, nine migrations later.
    *
    * `DeviceUserRow` and `NextIdentificationSurveyRow` are also unmapped and deliberately left so:
    * neither appears in a `query[...]` anywhere, and the second is a projection rather than a
    * table. Mapping a type nothing reads would only suggest it is used.
    */
  inline given SchemaMeta[DeviceRow] = schemaMeta[DeviceRow]("devices")
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
  inline given SchemaMeta[LocationRow] = schemaMeta[LocationRow]("locations")
  inline given SchemaMeta[EventLogRow] = schemaMeta[EventLogRow]("event_log")
end schema
