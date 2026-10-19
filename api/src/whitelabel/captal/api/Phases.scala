package whitelabel.captal.api

import whitelabel.captal.core.application.{FallbackPhase, NextStep, Phase}

/** Where the portal sends a visitor next, as values rather than as logic. Read top to bottom, this
  * is the order a guest moves through.
  *
  *   - `nextAfterX: NextStep` — emitted on success by the `Answer*` and `MarkVideoWatched`
  *     handlers, and serialised to the client as the response body.
  *   - `fallbackFromX: FallbackPhase` — where a `Provide*` handler sends the visitor when the
  *     location has no such resource, so they fall through to the next stop in the pipeline.
  */
private[api] object Phases:

  val nextAfterIdentification: NextStep = NextStep(Phase.AdvertiserVideo)
  val nextAfterVideo: NextStep = NextStep(Phase.AdvertiserVideoSurvey)
  val nextAfterAdvertiserSurvey: NextStep = NextStep(Phase.Ready)

  val fallbackFromIdentification: FallbackPhase = FallbackPhase(Phase.AdvertiserVideo)

  /** Where a visitor goes when the deployment can identify nobody — no user and no identification
    * survey: the flow ends rather than handing out access for nothing.
    */
  val unidentifiableFallback: FallbackPhase = FallbackPhase(Phase.Ready)
  val fallbackFromVideo: FallbackPhase = FallbackPhase(Phase.Ready)
  val fallbackFromAdvertiserSurvey: FallbackPhase = FallbackPhase(Phase.Ready)

end Phases
