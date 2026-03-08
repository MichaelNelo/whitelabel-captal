package whitelabel.captal.core.video

import io.circe.{Decoder, Encoder}

enum VideoType:
  case Ad
  case Promo

object VideoType:
  def toDbString(t: VideoType): String =
    t match
      case Ad =>
        "publicidad"
      case Promo =>
        "propaganda"

  def fromDbString(s: String): VideoType =
    s match
      case "publicidad" =>
        Ad
      case "propaganda" =>
        Promo
      case _ =>
        Promo

  given Encoder[VideoType] = Encoder.encodeString.contramap(toDbString)
  given Decoder[VideoType] = Decoder.decodeString.map(fromDbString)
end VideoType
