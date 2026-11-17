package whitelabel.captal.core.infrastructure

import whitelabel.captal.core.location.{LocationId, Slug}
import whitelabel.captal.core.video.Id
import whitelabel.captal.core.video.VideoType
import whitelabel.captal.core.video.authoring.{FileId, VideoStatus}

/** The file a video plays, with its length once read. */
final case class CurrentFile(id: FileId, durationSeconds: Option[Int])

/** The file a video waits for, reserved at `key` and not confirmed yet. */
final case class WaitingFile(id: FileId, key: String)

/** A video as its authoring reads it. */
final case class VideoInfo(
    id: Id,
    videoType: VideoType,
    status: VideoStatus,
    location: Option[LocationId],
    slug: Option[Slug],
    minWatchSeconds: Int,
    current: Option[CurrentFile],
    waiting: Option[WaitingFile])

/** A confirmed file not deleted yet: whether it is the one its video plays, and how many playbacks
  * of it are still open.
  */
final case class StoredFile(
    video: Id,
    id: FileId,
    url: String,
    key: String,
    current: Boolean,
    watching: Int)

trait VideoAuthoringRepository[F[_]]:
  def video(id: Id): F[Option[VideoInfo]]

  /** The videos in rotation at `location`; with none, those that belong to no location. */
  def activeAt(location: Option[LocationId]): F[List[Id]]

  /** The confirmed files not deleted yet, of the video `video` or of every one. */
  def files(video: Option[Id]): F[List[StoredFile]]
