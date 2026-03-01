package whitelabel.captal.core.survey.question

import whitelabel.captal.core.survey

final case class FullyQualifiedQuestionId(
    surveyId: survey.Id,
    questionId: Id
)
