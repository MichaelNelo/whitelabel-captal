package whitelabel.captal.core.user

/** User aggregate with typestate pattern.
  *
  * The type parameter S determines what data is loaded for the current flow.
  */
final case class User[S <: State](id: UserId, state: S)
