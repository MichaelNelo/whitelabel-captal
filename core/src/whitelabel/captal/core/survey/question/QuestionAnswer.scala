package whitelabel.captal.core.survey.question

import java.time.Instant

/** User's answer to a question */
final case class QuestionAnswer(
    questionId: QuestionId,
    value: AnswerValue,
    pointsAwarded: Int,
    answeredAt: Instant)
