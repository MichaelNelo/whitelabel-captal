package whitelabel.captal.endpoints

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import whitelabel.captal.core.application.commands.{
  NextAdvertiserSurvey,
  NextIdentificationSurvey,
  NextVideo
}
import whitelabel.captal.core.application.jsoniter.given
import whitelabel.captal.core.application.{IdentificationSurveyType, NextStep, Phase}
import whitelabel.captal.core.i18n.I18n
import whitelabel.captal.core.survey
import whitelabel.captal.core.survey.question.QuestionToAnswer
import whitelabel.captal.core.survey.question.jsoniter.given
import whitelabel.captal.core.survey.question.jsoniter.verbose

/** The wire types, read and written by jsoniter with the bytes circe produced.
  *
  * Verified rather than assumed: `JsoniterEndpointParityTests` generates values and compares the
  * two encoders string for string, the same way `core` does.
  */
object jsoniter:

  // ─────────────────────────────────────────────────────────────────────────────
  // Flat discriminators
  // ─────────────────────────────────────────────────────────────────────────────

  /** The three response unions do not nest their payload under a key: circe merges the payload's
    * own fields into the top level, with `"type"` written first and the fields after it.
    *
    * The obvious translation — encode the payload with `writeToArray` and splice the tag into the
    * bytes — is wrong, and wrong in a way worth recording. jsoniter's `writeToArray` borrows a
    * buffer pooled per thread, so calling it from inside an `encodeValue` that is already using
    * that buffer corrupts the output: the first attempt emitted
    * `{"phase":"ready"},{"phase":"ready","type":"step"}`.
    *
    * So the payloads write their fields straight into the writer already in hand. That is what
    * `writeFields` is for, and why these four codecs are spelled out rather than generated.
    */
  private trait Flat[A]:
    def writeFields(x: A, out: JsonWriter): Unit

  private val question = summon[JsonValueCodec[QuestionToAnswer]]
  private val phase = summon[JsonValueCodec[Phase]]
  private val surveyType = summon[JsonValueCodec[IdentificationSurveyType]]
  private val surveyId = summon[JsonValueCodec[survey.Id]]
  private val advertiserId = summon[JsonValueCodec[survey.AdvertiserId]]

  private val flatStep: Flat[NextStep] =
    (x, out) =>
      out.writeKey("phase");
      phase.encodeValue(x.phase, out)

  private val flatIdentification: Flat[NextIdentificationSurvey] =
    (x, out) =>
      out.writeKey("surveyId");
      surveyId.encodeValue(x.surveyId, out)
      out.writeKey("surveyType");
      surveyType.encodeValue(x.surveyType, out)
      out.writeKey("question");
      question.encodeValue(x.question, out)

  private val flatAdvertiser: Flat[NextAdvertiserSurvey] =
    (x, out) =>
      out.writeKey("surveyId");
      surveyId.encodeValue(x.surveyId, out)
      out.writeKey("advertiserId");
      advertiserId.encodeValue(x.advertiserId, out)
      out.writeKey("question");
      question.encodeValue(x.question, out)

  private val flatVideo: Flat[NextVideo] =
    (x, out) =>
      out.writeKey("videoUrl");
      out.writeVal(x.videoUrl)
      out.writeKey("durationSeconds");
      out.writeVal(x.durationSeconds)
      // Written rather than omitted when absent, because circe writes `null` and the point is parity.
      out.writeKey("title");
      x.title.fold(out.writeNull())(out.writeVal)
      out.writeKey("description");
      x.description.fold(out.writeNull())(out.writeVal)

  /** Decoding reads the whole object once into a slot per field name across every case, then picks
    * by the tag. `"type"` is written first but JSON does not promise it arrives first.
    */
  private def taggedCodec[A](
      tagOf: A => String,
      write: (A, JsonWriter) => Unit,
      build: (String, Fields) => A): JsonValueCodec[A] =
    new JsonValueCodec[A]:
      def nullValue: A = null.asInstanceOf[A]
      def encodeValue(x: A, out: JsonWriter): Unit =
        out.writeObjectStart()
        out.writeKey("type")
        out.writeVal(tagOf(x))
        write(x, out)
        out.writeObjectEnd()
      def decodeValue(in: JsonReader, default: A): A =
        val f = Fields()
        if !in.isNextToken('{') then
          in.decodeError("expected '{'")
        if !in.isNextToken('}') then
          in.rollbackToken()
          while
            in.readKeyAsString() match
              case "type" =>
                f.tag = in.readString(null)
              case "phase" =>
                f.phase = phase.decodeValue(in, Phase.Welcome)
              case "surveyId" =>
                f.surveyId = surveyId.decodeValue(in, f.surveyId)
              case "surveyType" =>
                f.surveyType = surveyType.decodeValue(in, f.surveyType)
              case "advertiserId" =>
                f.advertiserId = advertiserId.decodeValue(in, f.advertiserId)
              case "question" =>
                f.question = question.decodeValue(in, f.question)
              case "videoUrl" =>
                f.videoUrl = in.readString(null)
              case "durationSeconds" =>
                f.durationSeconds = in.readInt()
              case "title" =>
                f.title = Option(in.readString(null))
              case "description" =>
                f.description = Option(in.readString(null))
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
        if f.tag == null then
          in.decodeError("missing type")
        build(f.tag, f)
      end decodeValue

  final private class Fields:
    var tag: String = null
    var phase: Phase = Phase.Welcome
    var surveyId: survey.Id = null.asInstanceOf[survey.Id]
    var surveyType: IdentificationSurveyType = null.asInstanceOf[IdentificationSurveyType]
    var advertiserId: survey.AdvertiserId = null.asInstanceOf[survey.AdvertiserId]
    var question: QuestionToAnswer = null.asInstanceOf[QuestionToAnswer]
    var videoUrl: String = null
    var durationSeconds: Int = 0
    var title: Option[String] = None
    var description: Option[String] = None

  given JsonValueCodec[NextStep] = JsonCodecMaker.make(verbose)
  given JsonValueCodec[NextIdentificationSurvey] = JsonCodecMaker.make(verbose)
  given JsonValueCodec[NextAdvertiserSurvey] = JsonCodecMaker.make(verbose)
  given JsonValueCodec[NextVideo] = JsonCodecMaker.make(verbose)

  given JsonValueCodec[SurveyResponse] = taggedCodec(
    {
      case SurveyResponse.Survey(_) =>
        "survey"
      case SurveyResponse.Step(_) =>
        "step"
    },
    {
      case (SurveyResponse.Survey(d), out) =>
        flatIdentification.writeFields(d, out)
      case (SurveyResponse.Step(d), out) =>
        flatStep.writeFields(d, out)
    },
    {
      case ("survey", f) =>
        SurveyResponse.Survey(NextIdentificationSurvey(f.surveyId, f.surveyType, f.question))
      case ("step", f) =>
        SurveyResponse.Step(NextStep(f.phase))
      case (other, _) =>
        throw new IllegalArgumentException(s"Unknown type: $other")
    }
  )

  given JsonValueCodec[AdvertiserSurveyResponse] = taggedCodec(
    {
      case AdvertiserSurveyResponse.Survey(_) =>
        "survey"
      case AdvertiserSurveyResponse.Step(_) =>
        "step"
    },
    {
      case (AdvertiserSurveyResponse.Survey(d), out) =>
        flatAdvertiser.writeFields(d, out)
      case (AdvertiserSurveyResponse.Step(d), out) =>
        flatStep.writeFields(d, out)
    },
    {
      case ("survey", f) =>
        AdvertiserSurveyResponse.Survey(
          NextAdvertiserSurvey(f.surveyId, f.advertiserId, f.question))
      case ("step", f) =>
        AdvertiserSurveyResponse.Step(NextStep(f.phase))
      case (other, _) =>
        throw new IllegalArgumentException(s"Unknown type: $other")
    }
  )

  given JsonValueCodec[VideoResponse] = taggedCodec(
    {
      case VideoResponse.Video(_) =>
        "video"
      case VideoResponse.Step(_) =>
        "step"
    },
    {
      case (VideoResponse.Video(d), out) =>
        flatVideo.writeFields(d, out)
      case (VideoResponse.Step(d), out) =>
        flatStep.writeFields(d, out)
    },
    {
      case ("video", f) =>
        VideoResponse.Video(NextVideo(f.videoUrl, f.durationSeconds, f.title, f.description))
      case ("step", f) =>
        VideoResponse.Step(NextStep(f.phase))
      case (other, _) =>
        throw new IllegalArgumentException(s"Unknown type: $other")
    }
  )

  // ─────────────────────────────────────────────────────────────────────────────
  // Plain records
  // ─────────────────────────────────────────────────────────────────────────────

  given JsonValueCodec[StatusResponse] = JsonCodecMaker.make(verbose)
  given JsonValueCodec[AnswerRequest] = JsonCodecMaker.make(verbose)
  given JsonValueCodec[SetLocaleRequest] = JsonCodecMaker.make(verbose)
  given JsonValueCodec[MarkVideoWatchedRequest] = JsonCodecMaker.make(verbose)
  given JsonValueCodec[VideoWatchedResponse] = JsonCodecMaker.make(verbose)
  given JsonValueCodec[I18n] = JsonCodecMaker.make(verbose)
  given JsonValueCodec[List[String]] = JsonCodecMaker.make(verbose)
end jsoniter
