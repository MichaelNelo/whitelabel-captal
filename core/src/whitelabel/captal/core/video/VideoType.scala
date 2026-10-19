package whitelabel.captal.core.video

import io.circe.{Decoder, Encoder}

enum VideoType:
  case Ad
  case Promo

object VideoType:
  /** The word stored in the database: the case's own name, lowercased. */
  def toDbString(t: VideoType): String =
    t match
      case Ad =>
        "ad"
      case Promo =>
        "promo"

  /** Inverse of [[toDbString]]. `None` for anything else — there is no safe default kind. */
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
