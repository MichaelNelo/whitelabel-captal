package whitelabel.captal.core.survey

import whitelabel.captal.core.survey.question.{HierarchyLevel, QuestionToAnswer}

enum State:
  /** Email question (first mandatory question) */
  case WithEmailQuestion(question: QuestionToAnswer)

  /** Profiling question to answer */
  case WithProfilingQuestion(question: QuestionToAnswer)

  /** Location question to answer (options already filtered in QuestionType) */
  case WithLocationQuestion(question: QuestionToAnswer, hierarchyLevel: HierarchyLevel)

  /** Advertiser question to answer */
  case WithAdvertiserQuestion(advertiserId: AdvertiserId, question: QuestionToAnswer)
