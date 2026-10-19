package whitelabel.captal.core.survey

import whitelabel.captal.core.survey.question.{HierarchyLevel, QuestionToAnswer}

enum State:
  case WithEmailQuestion(question: QuestionToAnswer)
  case WithProfilingQuestion(question: QuestionToAnswer)
  case WithLocationQuestion(question: QuestionToAnswer, hierarchyLevel: HierarchyLevel)

  /** The survey that follows a video. `advertiserId` is `None` for a promo, which has no
    * advertiser; it is never persisted (`answers` has no advertiser column) and exists only so the
    * event log can attribute the answer.
    */
  case WithAdvertiserQuestion(advertiserId: Option[AdvertiserId], question: QuestionToAnswer)
