package whitelabel.captal.core.survey

/** Survey aggregate with typestate pattern.
  *
  * The type parameter S determines what data is loaded for the current flow. The category is
  * implicit in S:
  *   - Survey[State.WithEmailQuestion] -> (first mandatory question)
  *   - Survey[State.WithProfilingQuestion] -> Profiling
  *   - Survey[State.WithLocationQuestion] -> Location
  *   - Survey[State.WithAdvertiserQuestion] -> Advertiser
  */
final case class Survey[S <: State](id: SurveyId, state: S)
