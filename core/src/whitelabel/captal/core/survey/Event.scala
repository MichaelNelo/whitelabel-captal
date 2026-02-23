package whitelabel.captal.core.survey

import whitelabel.captal.core.survey.question.Event as QuestionEvent

enum Event:
  case QuestionAnswered(surveyId: Id, event: QuestionEvent)
