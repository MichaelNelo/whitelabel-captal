package whitelabel.captal.core.survey.question

/** Option for selection questions (Radio, Checkbox, Select) */
final case class QuestionOption(
    id: OptionId,
    text: LocalizedText,
    displayOrder: Int,
    parentOptionId: Option[OptionId])
