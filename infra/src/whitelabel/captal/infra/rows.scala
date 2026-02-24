package whitelabel.captal.infra

final case class UserRow(
    id: String,
    email: Option[String],
    locale: String,
    createdAt: String,
    updatedAt: String)

final case class SessionRow(
    id: String,
    userId: Option[String],
    deviceId: String,
    locale: String,
    phase: String,
    currentSurveyId: Option[String],
    currentQuestionId: Option[String],
    createdAt: String)

final case class SurveyRow(
    id: String,
    category: String,
    advertiserId: Option[String],
    isActive: Int,
    createdAt: String)

final case class QuestionRow(
    id: String,
    surveyId: String,
    questionType: String,
    textContent: String,
    textLocale: String,
    descriptionContent: Option[String],
    descriptionLocale: Option[String],
    pointsAwarded: Int,
    displayOrder: Int,
    hierarchyLevel: Option[String],
    isRequired: Int,
    createdAt: String)

final case class QuestionOptionRow(
    id: String,
    questionId: String,
    textContent: String,
    textLocale: String,
    displayOrder: Int,
    parentOptionId: Option[String])

final case class QuestionRuleRow(
    id: String,
    questionId: String,
    ruleType: String,
    ruleConfig: String)

final case class AnswerRow(
    id: String,
    userId: String,
    sessionId: String,
    questionId: String,
    answerValue: String,
    answeredAt: String,
    createdAt: String)

final case class UserSurveyProgressRow(
    id: String,
    userId: String,
    surveyId: String,
    currentQuestionId: Option[String],
    completedAt: Option[String],
    createdAt: String,
    updatedAt: String)

final case class NextIdentificationSurveyRow(surveyId: String, questionId: String, category: String)
