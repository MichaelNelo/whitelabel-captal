package whitelabel.captal.core.video.authoring

import java.time.Instant
import java.util.UUID

import whitelabel.captal.core.location.{LocationId, Slug}
import whitelabel.captal.core.survey
import whitelabel.captal.core.survey.AdvertiserId
import whitelabel.captal.core.survey.question.LocalizedText
import whitelabel.captal.core.video.{Id, VideoType}

/** One of a video's files. A video plays one at a time, and takes another by replacing it. */
opaque type FileId = String

object FileId:
  def apply(value: String): FileId = value

  /** A video's first file takes the video's own id. */
  def first(video: Id): FileId = video.asString

  def generate: FileId = UUID.randomUUID.toString

  extension (id: FileId)
    def asString: String = id

object VideoFile:
  /** Where the file `file` of a video of the location `slug` is stored: under the location's
    * prefix, which is why a video cannot move to another location once it has a file.
    */
  def keyOf(slug: Slug, file: FileId): String = s"${slug.asString}/videos/${file.asString}"

/** Where a video stands: registered with no file and invisible to guests, in rotation, or out of it
  * with its file and its answers kept.
  */
enum VideoStatus:
  case Pending,
    Active,
    Retired

/** Whose a video is: an advertiser's, weighted against the advertiser's others, with the client's
  * own campaign id if they gave one; or the venue's own promo, served in order and kept from the
  * same guest for `noRepeatSeconds`.
  */
enum VideoKind:
  case Advertiser(advertiser: AdvertiserId, productCampaignId: Option[String])
  case Promo(noRepeatSeconds: Option[Int])

  def videoType: VideoType =
    this match
      case Advertiser(_, _) =>
        VideoType.Ad
      case Promo(_) =>
        VideoType.Promo

/** Which of a video's texts a problem is in. */
enum VideoText:
  case Title,
    Description

object VideoRules:
  /** How long a guest watches before the survey unlocks, for a file `duration` long: the whole of
    * it for zero, and never past its end, since a survey that unlocks after the video ends never
    * unlocks at all.
    */
  def minWatch(requested: Int, duration: Int): Int =
    if requested <= 0 then
      duration
    else
      math.min(requested, duration)

  /** What a video is called where one name is needed — its survey's name: its Spanish title, or the
    * first it has.
    */
  def titleOf(title: List[LocalizedText]): String = title
    .find(_.locale == "es")
    .orElse(title.headOption)
    .map(_.value)
    .getOrElse("video")

  /** Every reason the texts written in `field` cannot be shown: an entry with a blank locale or
    * value, and a locale given twice.
    */
  def textProblems(field: VideoText, written: List[LocalizedText]): List[Error] =
    val repeated = written.groupBy(_.locale).filter(_._2.sizeIs > 1).keys.toList.sorted
    List(
      Option.when(written.exists(t => t.locale.isBlank || t.value.isBlank))(Error.BlankText(field)),
      Option.when(repeated.nonEmpty)(Error.RepeatedLocales(field, repeated))
    ).flatten
end VideoRules

enum Event:
  /** A video of `kind` at the location `location`, waiting for its first file, `file`, at `key`.
    * `minWatchSeconds` zero means the whole video, whose length is not known until the file is.
    */
  case VideoRegistered(
      id: Id,
      location: LocationId,
      kind: VideoKind,
      title: List[LocalizedText],
      description: List[LocalizedText],
      minWatchSeconds: Int,
      priority: Int,
      file: FileId,
      key: String,
      occurredAt: Instant)

  /** A new file for the video, waiting at `key` to be uploaded; the video plays the one it has
    * until this one is confirmed.
    */
  case FileReserved(video: Id, file: FileId, key: String, occurredAt: Instant)

  /** The file arrived, `durationSeconds` long, and is the one the video plays from now; the video
    * stands at `status` after it, with the survey unlocking at `minWatchSeconds`.
    */
  case FileConfirmed(
      video: Id,
      file: FileId,
      durationSeconds: Int,
      minWatchSeconds: Int,
      status: VideoStatus,
      occurredAt: Instant)

  /** What is given replaces what it had, the texts in the locales given; the rest is kept.
    * `productCampaignId` holds `None` to clear it.
    */
  case VideoUpdated(
      video: Id,
      title: Option[List[LocalizedText]],
      description: Option[List[LocalizedText]],
      minWatchSeconds: Option[Int],
      priority: Option[Int],
      noRepeatSeconds: Option[Int],
      productCampaignId: Option[Option[String]],
      occurredAt: Instant)

  /** Out of the rotation, with its file and the answers to its survey kept. */
  case VideoRetired(video: Id, occurredAt: Instant)
  case VideoReinstated(video: Id, occurredAt: Instant)

  /** A file the video no longer plays, and nobody is still playing, is gone, its object at `key`
    * with it.
    */
  case FileDeleted(video: Id, file: FileId, key: String, occurredAt: Instant)
end Event

enum Error:
  case VideoNotFound(id: String)
  case AdvertiserNotFound(id: AdvertiserId)

  /** Its videos would never be drawn. */
  case AdvertiserRetired(name: String)

  case NoTitle
  case BlankText(field: VideoText)
  case RepeatedLocales(field: VideoText, locales: List[String])

  /** An advertiser's video with nothing to ask: the rotation only serves one whose survey still has
    * a question left, so it would never be shown.
    */
  case NoQuestions

  /** The question at `index`, from one, cannot be asked, for `error`. */
  case QuestionRefused(index: Int, error: survey.authoring.Error)

  case MinWatchNotPositive(seconds: Int)
  case NegativePriority(priority: Int)
  case NegativeNoRepeat(seconds: Int)
  case DurationNotPositive(seconds: Int)

  /** The video plays the file it has and waits for no other. */
  case NoFileWaiting(video: Id)

  /** The file checked is no longer the one the video waits for: another replaced it meanwhile. */
  case FileNoLongerWaiting(video: Id, file: FileId)

  /** Still waiting for its first file, which only its confirmation puts into rotation. */
  case StillPending(video: Id)

  /** It belongs to no location, so a file of its has no key. */
  case NoLocation(video: Id)
end Error
