package whitelabel.captal.core.survey

import whitelabel.captal.core.survey.question.{HierarchyLevel, QuestionToAnswer}

enum State:
  case WithEmailQuestion(question: QuestionToAnswer)
  case WithProfilingQuestion(question: QuestionToAnswer)
  case WithLocationQuestion(question: QuestionToAnswer, hierarchyLevel: HierarchyLevel)

  /** The survey that follows a video.
    *
    * `advertiserId` is optional because a promo has no advertiser. It was required, and that is
    * what made a promo's survey unreachable: `SurveyRepositoryQuill` builds this inside an `Option`
    * comprehension, so a survey with no advertiser yielded `None` and the portal moved on as though
    * the video had no questions left — no error raised, nothing logged.
    *
    * Nothing persists it; `answers` has no advertiser column. It is carried so the event log can
    * attribute the answer, and an answer to a promo's question belongs to the venue — which is to
    * say to nobody in particular.
    */
  case WithAdvertiserQuestion(advertiserId: Option[AdvertiserId], question: QuestionToAnswer)
