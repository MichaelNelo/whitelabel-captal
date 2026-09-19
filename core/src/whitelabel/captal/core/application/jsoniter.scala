package whitelabel.captal.core.application

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}

/** Both enums travel as bare strings, which is what jsoniter writes for cases that carry nothing
  * once the discriminator is off — and the spelling is already `enforce_snake_case` of the case
  * name. `IdentificationQuestion` is `identification_question` either way.
  *
  * So these agree with `Phase.toDbString` by construction rather than by a table repeated here. If
  * a case is ever added whose database spelling does not follow, `@named` on that case is where it
  * goes; the parity test is what would notice.
  */
object jsoniter:

  private inline def dbStrings = CodecMakerConfig
    .withDiscriminatorFieldName(None)
    .withAdtLeafClassNameMapper(x =>
      JsonCodecMaker.enforce_snake_case(JsonCodecMaker.simpleClassName(x)))

  given JsonValueCodec[Phase] = JsonCodecMaker.make(dbStrings)
  given JsonValueCodec[IdentificationSurveyType] = JsonCodecMaker.make(dbStrings)
end jsoniter
