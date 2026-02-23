package whitelabel.captal.core.survey.question

import java.time.LocalDate

import scala.annotation.targetName

import io.circe.{Decoder, Encoder, Json}

object codecs:
  given Decoder[SelectionRule] = Decoder.instance: cursor =>
    cursor
      .get[String]("type")
      .flatMap:
        case "min_selections" =>
          cursor.get[Int]("value").map(SelectionRule.MinSelections(_))
        case "max_selections" =>
          cursor.get[Int]("value").map(SelectionRule.MaxSelections(_))
        case other =>
          Left(io.circe.DecodingFailure(s"Unknown selection rule: $other", cursor.history))

  given Encoder[SelectionRule] = Encoder.instance:
    case SelectionRule.MinSelections(min) =>
      Json.obj("type" -> Json.fromString("min_selections"), "value" -> Json.fromInt(min))
    case SelectionRule.MaxSelections(max) =>
      Json.obj("type" -> Json.fromString("max_selections"), "value" -> Json.fromInt(max))

  given Decoder[TextRule] = Decoder.instance: cursor =>
    cursor
      .get[String]("type")
      .flatMap:
        case "min_length" =>
          cursor.get[Int]("value").map(TextRule.MinLength(_))
        case "max_length" =>
          cursor.get[Int]("value").map(TextRule.MaxLength(_))
        case "pattern" =>
          cursor.get[String]("value").map(TextRule.Pattern(_))
        case "email" =>
          Right(TextRule.Email)
        case "url" =>
          Right(TextRule.Url)
        case other =>
          Left(io.circe.DecodingFailure(s"Unknown text rule: $other", cursor.history))

  given Encoder[TextRule] = Encoder.instance:
    case TextRule.MinLength(min) =>
      Json.obj("type" -> Json.fromString("min_length"), "value" -> Json.fromInt(min))
    case TextRule.MaxLength(max) =>
      Json.obj("type" -> Json.fromString("max_length"), "value" -> Json.fromInt(max))
    case TextRule.Pattern(regex) =>
      Json.obj("type" -> Json.fromString("pattern"), "value" -> Json.fromString(regex))
    case TextRule.Email =>
      Json.obj("type" -> Json.fromString("email"))
    case TextRule.Url =>
      Json.obj("type" -> Json.fromString("url"))

  @targetName("decoderRangeRuleInt")
  given Decoder[RangeRule[Int]] = Decoder.instance: cursor =>
    cursor
      .get[String]("type")
      .flatMap:
        case "min" =>
          cursor.get[Int]("value").map(RangeRule.Min(_))
        case "max" =>
          cursor.get[Int]("value").map(RangeRule.Max(_))
        case other =>
          Left(io.circe.DecodingFailure(s"Unknown range rule: $other", cursor.history))

  @targetName("encoderRangeRuleInt")
  given Encoder[RangeRule[Int]] = Encoder.instance:
    case RangeRule.Min(v) =>
      Json.obj("type" -> Json.fromString("min"), "value" -> Json.fromInt(v))
    case RangeRule.Max(v) =>
      Json.obj("type" -> Json.fromString("max"), "value" -> Json.fromInt(v))

  @targetName("decoderRangeRuleBigDecimal")
  given Decoder[RangeRule[BigDecimal]] = Decoder.instance: cursor =>
    cursor
      .get[String]("type")
      .flatMap:
        case "min" =>
          cursor.get[BigDecimal]("value").map(RangeRule.Min(_))
        case "max" =>
          cursor.get[BigDecimal]("value").map(RangeRule.Max(_))
        case other =>
          Left(io.circe.DecodingFailure(s"Unknown range rule: $other", cursor.history))

  @targetName("encoderRangeRuleBigDecimal")
  given Encoder[RangeRule[BigDecimal]] = Encoder.instance:
    case RangeRule.Min(v) =>
      Json.obj("type" -> Json.fromString("min"), "value" -> Json.fromBigDecimal(v))
    case RangeRule.Max(v) =>
      Json.obj("type" -> Json.fromString("max"), "value" -> Json.fromBigDecimal(v))

  @targetName("decoderRangeRuleLocalDate")
  given Decoder[RangeRule[LocalDate]] = Decoder.instance: cursor =>
    cursor
      .get[String]("type")
      .flatMap:
        case "min" =>
          cursor.get[String]("value").map(s => RangeRule.Min(LocalDate.parse(s)))
        case "max" =>
          cursor.get[String]("value").map(s => RangeRule.Max(LocalDate.parse(s)))
        case other =>
          Left(io.circe.DecodingFailure(s"Unknown range rule: $other", cursor.history))

  @targetName("encoderRangeRuleLocalDate")
  given Encoder[RangeRule[LocalDate]] = Encoder.instance:
    case RangeRule.Min(v) =>
      Json.obj("type" -> Json.fromString("min"), "value" -> Json.fromString(v.toString))
    case RangeRule.Max(v) =>
      Json.obj("type" -> Json.fromString("max"), "value" -> Json.fromString(v.toString))

  given Decoder[HierarchyLevel] = Decoder
    .decodeString
    .emap:
      case "state" =>
        Right(HierarchyLevel.State)
      case "city" =>
        Right(HierarchyLevel.City)
      case "municipality" =>
        Right(HierarchyLevel.Municipality)
      case "urbanization" =>
        Right(HierarchyLevel.Urbanization)
      case other =>
        Left(s"Unknown hierarchy level: $other")

  given Encoder[HierarchyLevel] = Encoder
    .encodeString
    .contramap:
      case HierarchyLevel.State =>
        "state"
      case HierarchyLevel.City =>
        "city"
      case HierarchyLevel.Municipality =>
        "municipality"
      case HierarchyLevel.Urbanization =>
        "urbanization"
  given Encoder[AnswerValue] = Encoder.instance:
    case AnswerValue.SingleChoice(optionId) =>
      Json.obj("type" -> Json.fromString("single"), "value" -> Json.fromString(optionId.asString))
    case AnswerValue.MultipleChoice(optionIds) =>
      Json.obj(
        "type" -> Json.fromString("multiple"),
        "value" -> Json.arr(optionIds.map(id => Json.fromString(id.asString)).toSeq*))
    case AnswerValue.Text(value) =>
      Json.obj("type" -> Json.fromString("text"), "value" -> Json.fromString(value))
    case AnswerValue.Rating(value) =>
      Json.obj("type" -> Json.fromString("rating"), "value" -> Json.fromFloatOrString(value))
    case AnswerValue.Numeric(value) =>
      Json.obj("type" -> Json.fromString("numeric"), "value" -> Json.fromBigDecimal(value))
    case AnswerValue.DateValue(value) =>
      Json.obj("type" -> Json.fromString("date"), "value" -> Json.fromString(value.toString))

  given Decoder[AnswerValue] = Decoder.instance: cursor =>
    cursor
      .get[String]("type")
      .flatMap:
        case "single" =>
          cursor
            .get[String]("value")
            .flatMap(s =>
              OptionId
                .fromString(s)
                .toRight(io.circe.DecodingFailure(s"Invalid option id: $s", cursor.history)))
            .map(AnswerValue.SingleChoice(_))
        case "multiple" =>
          cursor
            .get[List[String]]("value")
            .flatMap: ids =>
              val parsed = ids.flatMap(OptionId.fromString)
              if parsed.size == ids.size then
                Right(AnswerValue.MultipleChoice(parsed.toSet))
              else
                Left(io.circe.DecodingFailure("Invalid option ids", cursor.history))
        case "text" =>
          cursor.get[String]("value").map(AnswerValue.Text(_))
        case "rating" =>
          cursor.get[Float]("value").map(AnswerValue.Rating(_))
        case "numeric" =>
          cursor.get[BigDecimal]("value").map(AnswerValue.Numeric(_))
        case "date" =>
          cursor.get[String]("value").map(s => AnswerValue.DateValue(LocalDate.parse(s)))
        case other =>
          Left(io.circe.DecodingFailure(s"Unknown answer type: $other", cursor.history))
end codecs
