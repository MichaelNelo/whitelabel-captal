package whitelabel.captal.core.user

final case class User[S <: State](id: Id, state: S)
