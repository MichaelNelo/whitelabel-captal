package whitelabel.captal.core.survey.question

final case class QuestionOption(
    id: OptionId,
    text: LocalizedText,
    displayOrder: Int,
    parentOptionId: Option[OptionId])
