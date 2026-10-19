package whitelabel.captal.core.application

/** Phase to redirect to when a `Provide*` command cannot fulfill its job — no next identification
  * survey, no video, no advertiser survey for the current location. A separate type from
  * [[NextStep]], the success transition emitted by `Answer*` / `Mark*` and sent to the client.
  */
final case class FallbackPhase(phase: Phase)
