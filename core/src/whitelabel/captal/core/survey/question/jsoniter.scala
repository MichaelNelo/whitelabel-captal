package whitelabel.captal.core.survey.question

import java.time.LocalDate

import scala.annotation.targetName

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}
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

  /** Discriminated on `"type"`, payload under `"value"`. Shared by the three `RangeRule`
    * instantiations, which differ only in how the payload is read and written.
    */
  private def rangeRuleCodec[A](
      read: JsonReader => A,
      write: (JsonWriter, A) => Unit): JsonValueCodec[RangeRule[A]] =
    new JsonValueCodec[RangeRule[A]]:
      def nullValue: RangeRule[A] = null.asInstanceOf[RangeRule[A]]

      def decodeValue(in: JsonReader, default: RangeRule[A]): RangeRule[A] =
        if !in.isNextToken('{') then
          in.decodeError("expected '{'")
        var kind: String = null
        var value: Option[A] = None
        if !in.isNextToken('}') then
          in.rollbackToken()
          while
            in.readKeyAsString() match
              case "type" =>
                kind = in.readString(null)
              case "value" =>
                value = Some(read(in))
              case _ =>
                in.skip()
            in.isNextToken(',') do
            (
          )
          if !in.isCurrentToken('}') then
            in.decodeError("expected '}'")
        (kind, value) match
          case ("min", Some(v)) =>
            RangeRule.Min(v)
          case ("max", Some(v)) =>
            RangeRule.Max(v)
          case (other, _) =>
            in.decodeError(s"Unknown range rule: $other")
      end decodeValue

      def encodeValue(x: RangeRule[A], out: JsonWriter): Unit =
        out.writeObjectStart()
        out.writeKey("type")
        x match
          case RangeRule.Min(_) =>
            out.writeVal("min")
          case RangeRule.Max(_) =>
            out.writeVal("max")
        out.writeKey("value")
        x match
          case RangeRule.Min(v) =>
            write(out, v)
          case RangeRule.Max(v) =>
            write(out, v)
        out.writeObjectEnd()

  @targetName("codecRangeRuleInt")
  given JsonValueCodec[RangeRule[Int]] = rangeRuleCodec(_.readInt(), (o, v) => o.writeVal(v))

  @targetName("codecRangeRuleBigDecimal")
  given JsonValueCodec[RangeRule[BigDecimal]] = rangeRuleCodec(
    _.readBigDecimal(null),
    (o, v) => o.writeVal(v))

  @targetName("codecRangeRuleLocalDate")
  given JsonValueCodec[RangeRule[LocalDate]] = rangeRuleCodec(
    in => IsoDate.parse(in.readString(null)),
    (o, v) => o.writeVal(v.toString))

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
end jsoniter
