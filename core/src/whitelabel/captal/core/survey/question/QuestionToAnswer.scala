package whitelabel.captal.core.survey.question

final case class QuestionToAnswer(
    id: Id,
    text: LocalizedText,
    description: Option[LocalizedText],
    questionType: QuestionType,
    commonRules: List[CommonRule],
    pointsAwarded: Int)
