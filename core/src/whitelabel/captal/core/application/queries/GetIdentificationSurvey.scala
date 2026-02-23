package whitelabel.captal.core.application.queries

import whitelabel.captal.core.survey
import whitelabel.captal.core.survey.question.{HierarchyLevel, QuestionToAnswer}

case object GetIdentificationSurvey
enum IdentificationSurveyResponse:
  case Email(surveyId: survey.Id, question: QuestionToAnswer)
  case Profiling(surveyId: survey.Id, question: QuestionToAnswer)
  case Location(surveyId: survey.Id, question: QuestionToAnswer, hierarchyLevel: HierarchyLevel)
  case Completed
