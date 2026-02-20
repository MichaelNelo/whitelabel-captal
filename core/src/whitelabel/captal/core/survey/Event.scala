package whitelabel.captal.core.survey

import whitelabel.captal.core.survey.question.Event as QuestionEvent

/** Survey aggregate events */
enum Event:
  case QuestionAnswered(surveyId: SurveyId, event: QuestionEvent)
