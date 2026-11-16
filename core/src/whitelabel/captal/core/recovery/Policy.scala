package whitelabel.captal.core.recovery

import java.time.Duration

import whitelabel.captal.core.video.Playback

/** One of a location's recovery settings: each of the [[WaitPolicy]]'s, and how long a playback
  * whose page never said it ended stays open.
  */
enum PolicySetting:
  case Maximum,
    Ceiling,
    ZeroBelow,
    VideoWeight,
    VideoAnswerWeight,
    IdentificationWeight,
    EmailWeight,
    IdentifiedFactor,
    WatchedFactor,
    RecoveryExponent,
    FabricatedFactor,
    RecurrentFactor,
    RecoveryWindow,
    RecurrenceWindow,
    PlaybackGrace

/** What a location set of its recovery settings. Whatever it leaves out is [[WaitPolicy.Default]]
  * and [[Playback.Grace]], and follows them if they change.
  */
final case class PolicyOverrides(
    maximum: Option[Duration] = None,
    ceiling: Option[Duration] = None,
    zeroBelow: Option[Duration] = None,
    videoWeight: Option[Double] = None,
    videoAnswerWeight: Option[Double] = None,
    identificationWeight: Option[Double] = None,
    emailWeight: Option[Double] = None,
    identifiedFactor: Option[Double] = None,
    watchedFactor: Option[Double] = None,
    recoveryExponent: Option[Double] = None,
    fabricatedFactor: Option[Double] = None,
    recurrentFactor: Option[Double] = None,
    recoveryWindow: Option[Duration] = None,
    recurrenceWindow: Option[Duration] = None,
    playbackGrace: Option[Duration] = None):

  /** The policy the location's portal offers a way out with. */
  def policy: WaitPolicy =
    val default = WaitPolicy.Default
    WaitPolicy(
      maximum = maximum.getOrElse(default.maximum),
      ceiling = ceiling.getOrElse(default.ceiling),
      zeroBelow = zeroBelow.getOrElse(default.zeroBelow),
      videoWeight = videoWeight.getOrElse(default.videoWeight),
      videoAnswerWeight = videoAnswerWeight.getOrElse(default.videoAnswerWeight),
      identificationWeight = identificationWeight.getOrElse(default.identificationWeight),
      emailWeight = emailWeight.getOrElse(default.emailWeight),
      identifiedFactor = identifiedFactor.getOrElse(default.identifiedFactor),
      watchedFactor = watchedFactor.getOrElse(default.watchedFactor),
      recoveryExponent = recoveryExponent.getOrElse(default.recoveryExponent),
      fabricatedFactor = fabricatedFactor.getOrElse(default.fabricatedFactor),
      recurrentFactor = recurrentFactor.getOrElse(default.recurrentFactor),
      recoveryWindow = recoveryWindow.getOrElse(default.recoveryWindow),
      recurrenceWindow = recurrenceWindow.getOrElse(default.recurrenceWindow)
    )
  end policy

  /** How long after its file would have finished a playback whose page never ended is closed. */
  def grace: Duration = playbackGrace.getOrElse(Playback.Grace)

  /** The settings it sets, in [[PolicySetting]]'s order. */
  def set: List[PolicySetting] = PolicySetting.values.toList.filter(valueOf(_).isDefined)

  /** The settings it sets below zero: every one is a length, a weight or a factor. */
  def negative: List[PolicySetting] = PolicySetting.values.toList.filter(valueOf(_).exists(_ < 0))

  /** These, with what `change` sets replacing them and the rest kept. */
  def updated(change: PolicyOverrides): PolicyOverrides = PolicyOverrides(
    maximum = change.maximum.orElse(maximum),
    ceiling = change.ceiling.orElse(ceiling),
    zeroBelow = change.zeroBelow.orElse(zeroBelow),
    videoWeight = change.videoWeight.orElse(videoWeight),
    videoAnswerWeight = change.videoAnswerWeight.orElse(videoAnswerWeight),
    identificationWeight = change.identificationWeight.orElse(identificationWeight),
    emailWeight = change.emailWeight.orElse(emailWeight),
    identifiedFactor = change.identifiedFactor.orElse(identifiedFactor),
    watchedFactor = change.watchedFactor.orElse(watchedFactor),
    recoveryExponent = change.recoveryExponent.orElse(recoveryExponent),
    fabricatedFactor = change.fabricatedFactor.orElse(fabricatedFactor),
    recurrentFactor = change.recurrentFactor.orElse(recurrentFactor),
    recoveryWindow = change.recoveryWindow.orElse(recoveryWindow),
    recurrenceWindow = change.recurrenceWindow.orElse(recurrenceWindow),
    playbackGrace = change.playbackGrace.orElse(playbackGrace)
  )

  /** These, with `settings` back to their default. */
  def without(settings: Iterable[PolicySetting]): PolicyOverrides =
    settings.foldLeft(this): (overrides, setting) =>
      setting match
        case PolicySetting.Maximum =>
          overrides.copy(maximum = None)
        case PolicySetting.Ceiling =>
          overrides.copy(ceiling = None)
        case PolicySetting.ZeroBelow =>
          overrides.copy(zeroBelow = None)
        case PolicySetting.VideoWeight =>
          overrides.copy(videoWeight = None)
        case PolicySetting.VideoAnswerWeight =>
          overrides.copy(videoAnswerWeight = None)
        case PolicySetting.IdentificationWeight =>
          overrides.copy(identificationWeight = None)
        case PolicySetting.EmailWeight =>
          overrides.copy(emailWeight = None)
        case PolicySetting.IdentifiedFactor =>
          overrides.copy(identifiedFactor = None)
        case PolicySetting.WatchedFactor =>
          overrides.copy(watchedFactor = None)
        case PolicySetting.RecoveryExponent =>
          overrides.copy(recoveryExponent = None)
        case PolicySetting.FabricatedFactor =>
          overrides.copy(fabricatedFactor = None)
        case PolicySetting.RecurrentFactor =>
          overrides.copy(recurrentFactor = None)
        case PolicySetting.RecoveryWindow =>
          overrides.copy(recoveryWindow = None)
        case PolicySetting.RecurrenceWindow =>
          overrides.copy(recurrenceWindow = None)
        case PolicySetting.PlaybackGrace =>
          overrides.copy(playbackGrace = None)

  /** The value it sets for `setting`, as a number: a length in seconds. */
  private def valueOf(setting: PolicySetting): Option[Double] =
    def seconds(length: Option[Duration]) = length.map(_.getSeconds.toDouble)
    setting match
      case PolicySetting.Maximum =>
        seconds(maximum)
      case PolicySetting.Ceiling =>
        seconds(ceiling)
      case PolicySetting.ZeroBelow =>
        seconds(zeroBelow)
      case PolicySetting.VideoWeight =>
        videoWeight
      case PolicySetting.VideoAnswerWeight =>
        videoAnswerWeight
      case PolicySetting.IdentificationWeight =>
        identificationWeight
      case PolicySetting.EmailWeight =>
        emailWeight
      case PolicySetting.IdentifiedFactor =>
        identifiedFactor
      case PolicySetting.WatchedFactor =>
        watchedFactor
      case PolicySetting.RecoveryExponent =>
        recoveryExponent
      case PolicySetting.FabricatedFactor =>
        fabricatedFactor
      case PolicySetting.RecurrentFactor =>
        recurrentFactor
      case PolicySetting.RecoveryWindow =>
        seconds(recoveryWindow)
      case PolicySetting.RecurrenceWindow =>
        seconds(recurrenceWindow)
      case PolicySetting.PlaybackGrace =>
        seconds(playbackGrace)
    end match
  end valueOf
end PolicyOverrides
