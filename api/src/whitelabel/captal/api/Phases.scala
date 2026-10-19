package whitelabel.captal.api

import whitelabel.captal.core.application.{FallbackPhase, NextStep, Phase}

/** Where the portal sends a visitor next, as values rather than as logic.
  *
  * Two kinds of transition, and the difference is what each means when it happens:
  *
  *   - `nextAfterX: NextStep` — emitted on success by the `Answer*` and `MarkVideoWatched`
  *     handlers, and serialised to the client as the response body.
  *   - `fallbackFromX: FallbackPhase` — where a `Provide*` handler sends the visitor when the
  *     location has no such resource, so they fall through to the next stop in the pipeline.
  *
  * Together they are the pipeline itself: reading this file top to bottom is reading the order a
  * guest moves through, which is why they are collected here instead of inlined at each handler.
  */
private[api] object Phases:

  val nextAfterIdentification: NextStep = NextStep(Phase.AdvertiserVideo)
  val nextAfterVideo: NextStep = NextStep(Phase.AdvertiserVideoSurvey)
  val nextAfterAdvertiserSurvey: NextStep = NextStep(Phase.Ready)

  val fallbackFromIdentification: FallbackPhase = FallbackPhase(Phase.AdvertiserVideo)

  /** No user and no identification survey means the deployment cannot identify anyone. Sending the
    * visitor on to the video would hand out access for nothing, so the flow ends here instead.
    */
  val unidentifiableFallback: FallbackPhase = FallbackPhase(Phase.Ready)
  val fallbackFromVideo: FallbackPhase = FallbackPhase(Phase.Ready)
  val fallbackFromAdvertiserSurvey: FallbackPhase = FallbackPhase(Phase.Ready)

end Phases
