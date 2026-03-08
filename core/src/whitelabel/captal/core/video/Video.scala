package whitelabel.captal.core.video

import whitelabel.captal.core.survey.AdvertiserId
import whitelabel.captal.core.survey.question.LocalizedText

final case class AdvertiserVideo(
    id: Id,
    advertiserId: Option[AdvertiserId],
    videoType: VideoType,
    videoUrl: String,
    durationSeconds: Int,
    minWatchSeconds: Int,
    showCountdown: Boolean,
    noRepeatSeconds: Option[Int],
    priority: Int)

final case class VideoToWatch(
    id: Id,
    advertiserId: Option[AdvertiserId],
    videoType: VideoType,
    videoUrl: String,
    durationSeconds: Int,
    title: Option[LocalizedText],
    description: Option[LocalizedText])
