package whitelabel.captal.infra

import io.getquill.*
import io.getquill.jdbczio.Quill

package object schema:
  type QuillSqlite = Quill.Sqlite[SnakeCase]

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
end schema
