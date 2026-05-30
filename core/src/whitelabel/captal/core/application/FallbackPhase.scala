package whitelabel.captal.core.application

/** Phase to redirect to when a `Provide*` command cannot fulfill its job — e.g. there is no next
  * identification survey, no video, or no advertiser survey available for the current location. The
  * user "falls back" to the next stop in the captive-portal pipeline.
  *
  * Distinct type from [[NextStep]] (which is the success transition emitted by `Answer*` / `Mark*`
  * commands and serialized to the client) — they are both Phase wrappers but represent different
  * intents.
  */
final case class FallbackPhase(phase: Phase)
