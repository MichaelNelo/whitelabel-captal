package whitelabel.captal.core.survey.question

/** Minimum data needed to display and validate a question */
final case class QuestionToAnswer(
    id: QuestionId,
    text: LocalizedText,
    description: Option[LocalizedText],
    questionType: QuestionType,
    commonRules: List[CommonRule],
    pointsAwarded: Int)
