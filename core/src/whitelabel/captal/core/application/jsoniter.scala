package whitelabel.captal.core.application

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}

/** `Phase` and `IdentificationSurveyType` travel as the strings the database uses, not as tagged
  * objects — so a derived codec would be wrong twice over, in shape and in spelling. Both delegate
  * to the same `toDbString`/`fromDbString` pair circe's codecs do.
  */
object jsoniter:

  given JsonValueCodec[Phase] =
    new JsonValueCodec[Phase]:
      def nullValue: Phase = Phase.Welcome
      def encodeValue(x: Phase, out: JsonWriter): Unit = out.writeVal(Phase.toDbString(x))
      def decodeValue(in: JsonReader, default: Phase): Phase = Phase.fromDbString(
        in.readString(null))

  given JsonValueCodec[IdentificationSurveyType] =
    new JsonValueCodec[IdentificationSurveyType]:
      def nullValue: IdentificationSurveyType = IdentificationSurveyType.Email
      def encodeValue(x: IdentificationSurveyType, out: JsonWriter): Unit = out.writeVal(
        IdentificationSurveyType.toDbString(x))
      def decodeValue(in: JsonReader, default: IdentificationSurveyType): IdentificationSurveyType =
        IdentificationSurveyType.fromDbString(in.readString(null))
end jsoniter
