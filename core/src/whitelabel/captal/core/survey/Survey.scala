package whitelabel.captal.core.survey

final case class Survey[S <: State](id: Id, state: S)
