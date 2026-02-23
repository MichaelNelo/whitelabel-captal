package whitelabel.captal.core.survey.question

import java.time.Instant

final case class QuestionAnswer(questionId: Id, value: AnswerValue, answeredAt: Instant)
