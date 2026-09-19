package whitelabel.captal.core.survey.question

import java.time.LocalDate

import scala.annotation.targetName

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}
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

  /** Derived, with the exceptions annotated on the cases themselves: three tags that do not follow
    * their case name, and two fields the wire calls `"value"`.
    */
  given JsonValueCodec[AnswerValue] = JsonCodecMaker.make(questionTypeConfig)

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

  /** Derived. Each case already names its field `options` or `rules`, which is what the wire calls
    * them, and the one tag that does not follow its case name — `Select`, written `"dropdown"` —
    * says so with `@named` on the case itself.
    *
    * That annotation was the piece that made the difference. Expressing the exception in the
    * leaf-name mapper needs a conditional, and the macro's compile-time interpreter supports
    * neither `==` nor `replace` on a String; `@named` moves the exception to where the exception
    * is. It also takes the six element codecs with it: they existed only because a hand-written
    * codec had to compose them explicitly.
    */
  inline def questionTypeConfig = verbose
    .withDiscriminatorFieldName(Some("type"))
    .withAdtLeafClassNameMapper(x => JsonCodecMaker.simpleClassName(x).toLowerCase)

  given JsonValueCodec[QuestionType] = JsonCodecMaker.make(questionTypeConfig)

  given JsonValueCodec[QuestionToAnswer] = JsonCodecMaker.make(verbose)
end jsoniter
