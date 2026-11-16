package whitelabel.captal.core.application.queries

import whitelabel.captal.core.location.Slug
import whitelabel.captal.core.video.VideoType
import whitelabel.captal.core.video.authoring.{FileId, VideoStatus}

/** The videos, of the location `slug` or of every one; retired ones only with `includeRetired`. */
final case class VideosOfQuery(slug: Option[Slug], includeRetired: Boolean)

/** A video, with the length of the file it plays — none while pending — and whether a new file
  * waits to take over from it.
  */
final case class VideoReport(
    id: String,
    title: Option[String],
    slug: Option[String],
    videoType: VideoType,
    advertiser: Option[String],
    status: VideoStatus,
    durationSeconds: Option[Int],
    minWatchSeconds: Int,
    priority: Int,
    replacing: Boolean)

/** The videos registered and still waiting for their first file, of the location `slug` or of every
  * one.
  */
final case class PendingUploadsQuery(slug: Option[Slug])

final case class PendingUploadReport(
    id: String,
    title: Option[String],
    slug: Option[String],
    createdAt: String)

/** The file the video `video` waits for, to be uploaded or confirmed. A signed URL to upload it to
  * is not state: the caller signs one for `key`.
  */
final case class UploadUrlQuery(video: String)

/** `url` is where the portal fetches the file from once it is confirmed. */
final case class WaitingUpload(
    video: String,
    title: Option[String],
    slug: Option[String],
    file: FileId,
    key: String,
    url: String)
