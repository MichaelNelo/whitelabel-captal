package whitelabel.captal.infra

import io.getquill.*
import io.getquill.jdbczio.Quill
import whitelabel.captal.core.application.Phase
import whitelabel.captal.core.{survey, user}

type QuillSqlite = Quill.Sqlite[SnakeCase]

object QuillSchema:
  // ─────────────────────────────────────────────────────────────────────────────
  // Schema Meta
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

  // ─────────────────────────────────────────────────────────────────────────────
  // Mapped Encodings - User types
  // ─────────────────────────────────────────────────────────────────────────────

  inline given MappedEncoding[user.Id, String] = MappedEncoding(_.asString)
  inline given MappedEncoding[String, user.Id] = MappedEncoding(user.Id.unsafe)

  inline given MappedEncoding[user.SessionId, String] = MappedEncoding(_.asString)
  inline given MappedEncoding[String, user.SessionId] = MappedEncoding(user.SessionId.unsafe)

  inline given MappedEncoding[user.DeviceId, String] = MappedEncoding(_.value)
  inline given MappedEncoding[String, user.DeviceId] = MappedEncoding(user.DeviceId(_))

  inline given MappedEncoding[user.Email, String] = MappedEncoding(_.value)
  inline given MappedEncoding[String, user.Email] = MappedEncoding(user.Email.unsafeFrom)

  // ─────────────────────────────────────────────────────────────────────────────
  // Mapped Encodings - Survey types
  // ─────────────────────────────────────────────────────────────────────────────

  inline given MappedEncoding[survey.Id, String] = MappedEncoding(_.asString)
  inline given MappedEncoding[String, survey.Id] = MappedEncoding(survey.Id.unsafe)

  inline given MappedEncoding[survey.question.Id, String] = MappedEncoding(_.asString)
  inline given MappedEncoding[String, survey.question.Id] = MappedEncoding(survey.question.Id.unsafe)

  inline given MappedEncoding[survey.question.OptionId, String] = MappedEncoding(_.asString)
  inline given MappedEncoding[String, survey.question.OptionId] =
    MappedEncoding(survey.question.OptionId.unsafe)

  // ─────────────────────────────────────────────────────────────────────────────
  // Mapped Encodings - Phase
  // ─────────────────────────────────────────────────────────────────────────────

  inline given MappedEncoding[Phase, String] = MappedEncoding(Phase.toDbString)
  inline given MappedEncoding[String, Phase] = MappedEncoding(Phase.fromDbString)
end QuillSchema
