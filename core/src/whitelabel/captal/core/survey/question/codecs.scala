package whitelabel.captal.core.survey.question

import java.time.LocalDate

import scala.annotation.targetName

import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}
import whitelabel.captal.core.survey

object codecs:
  // ─────────────────────────────────────────────────────────────────────────────
  // IDs
  // ─────────────────────────────────────────────────────────────────────────────

  given surveyIdEncoder: Encoder[survey.Id] = Encoder.encodeString.contramap(_.asString)
  given surveyIdDecoder: Decoder[survey.Id] = Decoder
    .decodeString
    .emap(s => survey.Id.fromString(s).toRight(s"Invalid survey id: $s"))

  given questionIdEncoder: Encoder[Id] = Encoder.encodeString.contramap(_.asString)
  given questionIdDecoder: Decoder[Id] = Decoder
    .decodeString
    .emap(s => Id.fromString(s).toRight(s"Invalid question id: $s"))

  given optionIdEncoder: Encoder[OptionId] = Encoder.encodeString.contramap(_.asString)
  given optionIdDecoder: Decoder[OptionId] = Decoder
    .decodeString
    .emap(s => OptionId.fromString(s).toRight(s"Invalid option id: $s"))

  // ─────────────────────────────────────────────────────────────────────────────
  // Selection Rules
  // ─────────────────────────────────────────────────────────────────────────────

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

  // ─────────────────────────────────────────────────────────────────────────────
  // Text Rules
  // ─────────────────────────────────────────────────────────────────────────────

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

  // ─────────────────────────────────────────────────────────────────────────────
  // Range Rules
  // ─────────────────────────────────────────────────────────────────────────────

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

  // ─────────────────────────────────────────────────────────────────────────────
  // Common Rules
  // ─────────────────────────────────────────────────────────────────────────────

  given Encoder[CommonRule] = Encoder
    .encodeString
    .contramap:
      case CommonRule.Required =>
        "required"

  given Decoder[CommonRule] = Decoder
    .decodeString
    .emap:
      case "required" =>
        Right(CommonRule.Required)
      case other =>
        Left(s"Unknown common rule: $other")

  // ─────────────────────────────────────────────────────────────────────────────
  // Hierarchy Level
  // ─────────────────────────────────────────────────────────────────────────────

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

  // ─────────────────────────────────────────────────────────────────────────────
  // LocalizedText & QuestionOption (derived)
  // ─────────────────────────────────────────────────────────────────────────────

  given Encoder[LocalizedText] = Encoder.AsObject.derived
  given Decoder[LocalizedText] = Decoder.derived

  given Encoder[QuestionOption] = Encoder.AsObject.derived
  given Decoder[QuestionOption] = Decoder.derived

  // ─────────────────────────────────────────────────────────────────────────────
  // QuestionType
  // ─────────────────────────────────────────────────────────────────────────────

  given Encoder[QuestionType] = Encoder.instance:
    case QuestionType.Radio(options) =>
      Json.obj("type" -> Json.fromString("radio"), "options" -> options.asJson)
    case QuestionType.Checkbox(options, rules) =>
      Json.obj(
        "type"    -> Json.fromString("checkbox"),
        "options" -> options.asJson,
        "rules"   -> rules.asJson)
    case QuestionType.Select(options) =>
      Json.obj("type" -> Json.fromString("select"), "options" -> options.asJson)
    case QuestionType.Input(rules) =>
      Json.obj("type" -> Json.fromString("input"), "rules" -> rules.asJson)
    case QuestionType.Rating(rules) =>
      Json.obj("type" -> Json.fromString("rating"), "rules" -> rules.asJson)
    case QuestionType.Numeric(rules) =>
      Json.obj("type" -> Json.fromString("numeric"), "rules" -> rules.asJson)
    case QuestionType.Date(rules) =>
      Json.obj("type" -> Json.fromString("date"), "rules" -> rules.asJson)

  given Decoder[QuestionType] = Decoder.instance: c =>
    c.get[String]("type")
      .flatMap:
        case "radio" =>
          c.get[List[QuestionOption]]("options").map(QuestionType.Radio(_))
        case "checkbox" =>
          for
            options <- c.get[List[QuestionOption]]("options")
            rules   <- c.getOrElse[List[SelectionRule]]("rules")(Nil)
          yield QuestionType.Checkbox(options, rules)
        case "select" =>
          c.get[List[QuestionOption]]("options").map(QuestionType.Select(_))
        case "input" =>
          c.getOrElse[List[TextRule]]("rules")(Nil).map(QuestionType.Input(_))
        case "rating" =>
          c.getOrElse[List[RangeRule[Int]]]("rules")(Nil).map(QuestionType.Rating(_))
        case "numeric" =>
          c.getOrElse[List[RangeRule[BigDecimal]]]("rules")(Nil).map(QuestionType.Numeric(_))
        case "date" =>
          c.getOrElse[List[RangeRule[LocalDate]]]("rules")(Nil).map(QuestionType.Date(_))
        case other =>
          Left(io.circe.DecodingFailure(s"Unknown question type: $other", c.history))

  // ─────────────────────────────────────────────────────────────────────────────
  // QuestionToAnswer (derived)
  // ─────────────────────────────────────────────────────────────────────────────

  given Encoder[QuestionToAnswer] = Encoder.AsObject.derived
  given Decoder[QuestionToAnswer] = Decoder.derived

  // ─────────────────────────────────────────────────────────────────────────────
  // AnswerValue
  // ─────────────────────────────────────────────────────────────────────────────

  given Encoder[AnswerValue] = Encoder.instance:
    case AnswerValue.SingleChoice(optionId) =>
      Json.obj("type" -> Json.fromString("single"), "value" -> Json.fromString(optionId.asString))
    case AnswerValue.MultipleChoice(optionIds) =>
      Json.obj(
        "type"  -> Json.fromString("multiple"),
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
