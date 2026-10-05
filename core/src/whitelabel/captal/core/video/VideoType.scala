package whitelabel.captal.core.video

import io.circe.{Decoder, Encoder}

enum VideoType:
  case Ad
  case Promo

object VideoType:
  /** The stored word, which since V22 is the case's own name.
    *
    * It used to be `publicidad` and `propaganda`. Nothing read those but this translation, and V5
    * had already renamed `last_propaganda_video_id` to `last_promo_video_id` without touching the
    * values — leaving a column whose name and contents disagreed, and a type whose cases had to be
    * mentally translated by anyone writing SQL.
    */
  def toDbString(t: VideoType): String =
    t match
      case Ad =>
        "ad"
      case Promo =>
        "promo"

  /** Parses, and says so when it cannot.
    *
    * This used to fall through to `Promo` for anything unrecognised, which turned a bad row — or a
    * database still holding the pre-V22 words — into a video quietly serving as the wrong kind. A
    * rotation is not a safe default for an advertiser's video.
    */
  def fromDbString(s: String): Option[VideoType] =
    s match
      case "ad" =>
        Some(Ad)
      case "promo" =>
        Some(Promo)
      case _ =>
        None

  given Encoder[VideoType] = Encoder.encodeString.contramap(toDbString)
  given Decoder[VideoType] = Decoder
    .decodeString
    .emap(s => fromDbString(s).toRight(s"Not a video type: '$s'"))
end VideoType
