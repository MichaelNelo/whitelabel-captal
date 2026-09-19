package whitelabel.captal.core.survey.question

import java.time.LocalDate

import scala.annotation.targetName

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReader,
  JsonValueCodec,
  JsonWriter,
  readFromString
}
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import whitelabel.captal.core.survey

/** The same wire format as [[codecs]], written against jsoniter's reader and writer.
  *
  * Hand-written rather than derived, because the format does not follow from the types. The
  * discriminator is a `"type"` field whose values are not the case names (`Select` is
  * `"dropdown"`), the payload always lives under `"value"` however the case spells its own field,
  * and a missing `"rules"` means an empty list rather than an error. A macro that guessed any of
  * that would guess differently.
  *
  * `JsoniterParityTests` is what makes this safe to swap in: it generates values and asserts these
  * produce byte-identical JSON to circe's.
  */
object jsoniter:

  /** Derived, not written out: `RangeRule`'s field is already called `value`, which is what the
    * wire calls it, so the discriminator config is the whole difference. The sibling rule families
    * do not get this — `SelectionRule.MinSelections` names its field `min` and the wire says
    * `"value"`, and jsoniter's field mapper is global rather than per case.
    */
  inline def tagged = verbose
    .withDiscriminatorFieldName(Some("type"))
    .withAdtLeafClassNameMapper(x => JsonCodecMaker.simpleClassName(x).toLowerCase)

  @targetName("codecRangeRuleInt")
  given JsonValueCodec[RangeRule[Int]] = JsonCodecMaker.make(tagged)

  @targetName("codecRangeRuleBigDecimal")
  given JsonValueCodec[RangeRule[BigDecimal]] = JsonCodecMaker.make(tagged)

  /** Derived too — jsoniter reads and writes `java.time.LocalDate` itself, and does it by hand
    * rather than through `DateTimeFormatter`, which is what `IsoDate` exists to avoid.
    */
  @targetName("codecRangeRuleLocalDate")
  given JsonValueCodec[RangeRule[LocalDate]] = JsonCodecMaker.make(tagged)

  given JsonValueCodec[AnswerValue] =
    new JsonValueCodec[AnswerValue]:
      def nullValue: AnswerValue = null.asInstanceOf[AnswerValue]

      def decodeValue(in: JsonReader, default: AnswerValue): AnswerValue =
        if !in.isNextToken('{') then
          in.decodeError("expected '{'")
        var kind: String = null
        // The payload's type depends on the discriminator, which JSON does not promise comes first.
        // So it is captured as a thunk and forced once both halves are known.
        var single: String = null
        var multiple: List[String] = Nil
        var number: BigDecimal = null
        var text: String = null
        if !in.isNextToken('}') then
          in.rollbackToken()
          while
            in.readKeyAsString() match
              case "type" =>
                kind = in.readString(null)
              case "value" =>
                if in.isNextToken('[') then
                  in.rollbackToken()
                  val buf = List.newBuilder[String]
                  if in.isNextToken('[') && !in.isNextToken(']') then
                    in.rollbackToken()
                    while
                      buf += in.readString(null)
                      in.isNextToken(',') do
                      (
                    )
                  multiple = buf.result()
                else
                  in.rollbackToken()
                  val t = in.nextToken()
                  in.rollbackToken()
                  if t == '"' then
                    val s = in.readString(null)
                    single = s
                    text = s
                  else
                    number = in.readBigDecimal(null)
              case _ =>
                in.skip()
            end match
            in.isNextToken(',') do
            (
          )
          end while
          if !in.isCurrentToken('}') then
            in.decodeError("expected '}'")
        end if
        kind match
          case "single" =>
            AnswerValue.SingleChoice(
              OptionId.fromString(single).getOrElse(in.decodeError(s"Invalid option id: $single")))
          case "multiple" =>
            val parsed = multiple.flatMap(OptionId.fromString)
            if parsed.size != multiple.size then
              in.decodeError("Invalid option ids")
            AnswerValue.MultipleChoice(parsed.toSet)
          case "text" =>
            AnswerValue.Text(text)
          case "rating" =>
            AnswerValue.Rating(number.toFloat)
          case "numeric" =>
            AnswerValue.Numeric(number)
          case "date" =>
            AnswerValue.DateValue(IsoDate.parse(text))
          case other =>
            in.decodeError(s"Unknown answer type: $other")
      end decodeValue

      def encodeValue(x: AnswerValue, out: JsonWriter): Unit =
        out.writeObjectStart()
        out.writeKey("type")
        x match
          case _: AnswerValue.SingleChoice =>
            out.writeVal("single")
          case _: AnswerValue.MultipleChoice =>
            out.writeVal("multiple")
          case _: AnswerValue.Text =>
            out.writeVal("text")
          case _: AnswerValue.Rating =>
            out.writeVal("rating")
          case _: AnswerValue.Numeric =>
            out.writeVal("numeric")
          case _: AnswerValue.DateValue =>
            out.writeVal("date")
        out.writeKey("value")
        x match
          case AnswerValue.SingleChoice(id) =>
            out.writeVal(id.asString)
          case AnswerValue.MultipleChoice(ids) =>
            out.writeArrayStart()
            ids.foreach(id => out.writeVal(id.asString))
            out.writeArrayEnd()
          case AnswerValue.Text(v) =>
            out.writeVal(v)
          case AnswerValue.Rating(v) =>
            out.writeVal(v)
          case AnswerValue.Numeric(v) =>
            out.writeVal(v)
          case AnswerValue.DateValue(v) =>
            out.writeVal(v.toString)
        out.writeObjectEnd()
      end encodeValue
  // ─────────────────────────────────────────────────────────────────────────────
  // IDs: opaque strings that validate on the way in
  // ─────────────────────────────────────────────────────────────────────────────

  private def idCodec[A](
      parse: String => Option[A],
      render: A => String,
      what: String): JsonValueCodec[A] =
    new JsonValueCodec[A]:
      def nullValue: A = null.asInstanceOf[A]
      def decodeValue(in: JsonReader, default: A): A =
        val s = in.readString(null)
        parse(s).getOrElse(in.decodeError(s"Invalid $what: $s"))
      def encodeValue(x: A, out: JsonWriter): Unit = out.writeVal(render(x))

  @targetName("codecSurveyId")
  given JsonValueCodec[survey.Id] = idCodec(survey.Id.fromString, _.asString, "survey id")
  @targetName("codecQuestionId")
  given JsonValueCodec[Id] = idCodec(Id.fromString, _.asString, "question id")
  @targetName("codecOptionId")
  given JsonValueCodec[OptionId] = idCodec(OptionId.fromString, _.asString, "option id")
  @targetName("codecAdvertiserId")
  given JsonValueCodec[survey.AdvertiserId] = idCodec(
    survey.AdvertiserId.fromString,
    _.asString,
    "advertiser id")

  // ─────────────────────────────────────────────────────────────────────────────
  // Enums carried as plain strings
  // ─────────────────────────────────────────────────────────────────────────────

  /** Both are enums whose cases carry nothing, which jsoniter writes as bare strings once the
    * discriminator is turned off — the same shape circe produced. Only the spelling needs saying.
    */
  inline def bareString = verbose
    .withDiscriminatorFieldName(None)
    .withAdtLeafClassNameMapper(x => JsonCodecMaker.simpleClassName(x).toLowerCase)

  given JsonValueCodec[CommonRule] = JsonCodecMaker.make(bareString)
  given JsonValueCodec[HierarchyLevel] = JsonCodecMaker.make(bareString)

  // ─────────────────────────────────────────────────────────────────────────────
  // Rules: `"type"` plus an optional `"value"` whose shape follows the tag
  // ─────────────────────────────────────────────────────────────────────────────

  /** Both derive. The tags fall out of `enforce_snake_case` over the case names — `MinSelections`
    * is `min_selections`, `MinLength` is `min_length`, `Email` is `email` — and the payload key is
    * a rename: these cases call their field `min`, `max` or `regex` where the wire says `"value"`.
    * The field mapper belongs to this `make` call rather than to the program, so saying it here
    * touches no other codec.
    *
    * `Email` and `Url` carry nothing and come out as `{"type":"email"}`, which is what circe wrote.
    */
  inline def taggedAsValue = verbose
    .withDiscriminatorFieldName(Some("type"))
    .withAdtLeafClassNameMapper(x =>
      JsonCodecMaker.enforce_snake_case(JsonCodecMaker.simpleClassName(x)))
    .withFieldNameMapper {
      case "min" =>
        "value"
      case "max" =>
        "value"
      case "regex" =>
        "value"
    }

  given JsonValueCodec[SelectionRule] = JsonCodecMaker.make(taggedAsValue)
  given JsonValueCodec[TextRule] = JsonCodecMaker.make(taggedAsValue)

  // ─────────────────────────────────────────────────────────────────────────────
  // Plain records
  // ─────────────────────────────────────────────────────────────────────────────

  /** jsoniter omits `None`, empty collections and default values; circe writes them out. Since the
    * point here is to leave the bytes where they are, every one of those is turned off.
    */
  inline def verbose = CodecMakerConfig
    .withTransientNone(false)
    .withTransientEmpty(false)
    .withTransientDefault(false)

  given JsonValueCodec[LocalizedText] = JsonCodecMaker.make(verbose)
  given JsonValueCodec[QuestionOption] = JsonCodecMaker.make(verbose)

  /** The lists `QuestionType` carries. Spelled out because its codec builds them by hand from the
    * tag, so they have to exist before it rather than be generated inside it.
    */
  given optionList: JsonValueCodec[List[QuestionOption]] = JsonCodecMaker.make(verbose)
  given selectionList: JsonValueCodec[List[SelectionRule]] = JsonCodecMaker.make(taggedAsValue)
  given textList: JsonValueCodec[List[TextRule]] = JsonCodecMaker.make(taggedAsValue)
  given intRuleList: JsonValueCodec[List[RangeRule[Int]]] = JsonCodecMaker.make(verbose)
  given decimalRuleList: JsonValueCodec[List[RangeRule[BigDecimal]]] = JsonCodecMaker.make(verbose)
  given dateRuleList: JsonValueCodec[List[RangeRule[LocalDate]]] = JsonCodecMaker.make(verbose)

  // ─────────────────────────────────────────────────────────────────────────────
  // QuestionType: `"type"` and a payload that differs per case, `Select` spelled "dropdown"
  // ─────────────────────────────────────────────────────────────────────────────

  given JsonValueCodec[QuestionType] =
    new JsonValueCodec[QuestionType]:
      def nullValue: QuestionType = null.asInstanceOf[QuestionType]

      def decodeValue(in: JsonReader, default: QuestionType): QuestionType =
        if !in.isNextToken('{') then
          in.decodeError("expected '{'")
        var kind: String = null
        var options: List[QuestionOption] = Nil
        // The rules arrive before their type is known, so each family gets its own slot and only
        // the one the tag names is used. A missing `"rules"` stays empty, as it does in circe.
        Nil
        Nil
        Nil
        Nil
        Nil
        var rawRules: String = null
        if !in.isNextToken('}') then
          in.rollbackToken()
          while
            in.readKeyAsString() match
              case "type" =>
                kind = in.readString(null)
              case "options" =>
                options = optionList.decodeValue(in, Nil)
              case "rules" =>
                rawRules = in.readRawValAsBytes().map(_.toChar).mkString
              case _ =>
                in.skip()
            in.isNextToken(',') do
            (
          )
          if !in.isCurrentToken('}') then
            in.decodeError("expected '}'")
        def rules[A](c: JsonValueCodec[List[A]]): List[A] =
          if rawRules == null then
            Nil
          else
            readFromString(rawRules)(using c)
        kind match
          case "radio" =>
            QuestionType.Radio(options)
          case "checkbox" =>
            QuestionType.Checkbox(options, rules(selectionList))
          case "dropdown" =>
            QuestionType.Select(options)
          case "input" =>
            QuestionType.Input(rules(textList))
          case "rating" =>
            QuestionType.Rating(rules(intRuleList))
          case "numeric" =>
            QuestionType.Numeric(rules(decimalRuleList))
          case "date" =>
            QuestionType.Date(rules(dateRuleList))
          case other =>
            in.decodeError(s"Unknown question type: $other")
      end decodeValue

      def encodeValue(x: QuestionType, out: JsonWriter): Unit =
        def list[A](vs: List[A], c: JsonValueCodec[List[A]]): Unit = c.encodeValue(vs, out)
        out.writeObjectStart()
        out.writeKey("type")
        x match
          case _: QuestionType.Radio =>
            out.writeVal("radio")
          case _: QuestionType.Checkbox =>
            out.writeVal("checkbox")
          case _: QuestionType.Select =>
            out.writeVal("dropdown")
          case _: QuestionType.Input =>
            out.writeVal("input")
          case _: QuestionType.Rating =>
            out.writeVal("rating")
          case _: QuestionType.Numeric =>
            out.writeVal("numeric")
          case _: QuestionType.Date =>
            out.writeVal("date")
        x match
          case QuestionType.Radio(o) =>
            out.writeKey("options");
            list(o, optionList)
          case QuestionType.Select(o) =>
            out.writeKey("options");
            list(o, optionList)
          case QuestionType.Checkbox(o, r) =>
            out.writeKey("options");
            list(o, optionList);
            out.writeKey("rules");
            list(r, selectionList)
          case QuestionType.Input(r) =>
            out.writeKey("rules");
            list(r, textList)
          case QuestionType.Rating(r) =>
            out.writeKey("rules");
            list(r, intRuleList)
          case QuestionType.Numeric(r) =>
            out.writeKey("rules");
            list(r, decimalRuleList)
          case QuestionType.Date(r) =>
            out.writeKey("rules");
            list(r, dateRuleList)
        end match
        out.writeObjectEnd()
      end encodeValue

  given JsonValueCodec[QuestionToAnswer] = JsonCodecMaker.make(verbose)
end jsoniter
