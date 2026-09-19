package whitelabel.captal.endpoints

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromString, writeToString}
import io.circe.syntax.*
import org.scalacheck.Prop.forAll
import org.scalacheck.{Gen, Prop}
import utest.*
import whitelabel.captal.core.application.commands.{
  NextAdvertiserSurvey,
  NextIdentificationSurvey,
  NextVideo
}
import whitelabel.captal.core.application.{IdentificationSurveyType, NextStep, Phase}
import whitelabel.captal.core.survey
import whitelabel.captal.core.survey.question.*
import whitelabel.captal.endpoints.jsoniter.given

/** The `endpoints` half of the same claim `core` makes: jsoniter writes what circe wrote.
  *
  * The three response unions are the reason this is not obvious. They do not nest their payload
  * under a key — circe merges the payload's own fields into the top level and appends `"type"`
  * beside them, so the tag lands last and the shape depends on which case it is. jsoniter cannot
  * splice objects, so it encodes the payload alone and appends the tag to the bytes. Whether those
  * two arrive at the same string is exactly what this asks.
  */
object JsoniterEndpointParityTests extends TestSuite:

  private def checkProp(prop: Prop): Unit =
    val result = prop.apply(Gen.Parameters.default)
    assert(result.success)

  /** Two claims in one, and the second was missing until a hand-rolled decoder turned out not to
    * accept `"description":null` — a value its own encoder had just written. Comparing what two
    * encoders emit says nothing about whether either can read it back.
    */
  private def parity[A: io.circe.Encoder: JsonValueCodec](gen: Gen[A]): Prop =
    forAll(gen): a =>
      val written = writeToString(a)
      written == a.asJson.noSpaces && readFromString[A](written) == a

  private val genPhase: Gen[Phase] = Gen.oneOf(
    Phase.Welcome,
    Phase.IdentificationQuestion,
    Phase.AdvertiserVideo,
    Phase.AdvertiserVideoSurvey,
    Phase.AdvertiserQuestion,
    Phase.Ready,
    Phase.Authorized)

  private val genStep: Gen[NextStep] = genPhase.map(NextStep.apply)

  private val genQuestion: Gen[QuestionToAnswer] =
    for
      text  <- Gen.alphaNumStr.map(LocalizedText(_, "es"))
      descr <- Gen.option(Gen.alphaNumStr.map(LocalizedText(_, "es")))
      order <- Gen.chooseNum(1, 20)
      opts = List(QuestionOption(OptionId.generate, text, order, None))
      points <- Gen.chooseNum(0, 100)
    yield QuestionToAnswer(
      Id.generate,
      text,
      descr,
      None,
      QuestionType.Radio(opts),
      List(CommonRule.Required),
      points)

  private val genIdentification: Gen[NextIdentificationSurvey] =
    for
      t <- Gen.oneOf(
        IdentificationSurveyType.Email,
        IdentificationSurveyType.Profiling,
        IdentificationSurveyType.Location)
      q <- genQuestion
    yield NextIdentificationSurvey(survey.Id.generate, t, q)

  private val genAdvertiser: Gen[NextAdvertiserSurvey] = genQuestion.map(q =>
    NextAdvertiserSurvey(survey.Id.generate, survey.AdvertiserId.generate, q))

  /** With and without the optional title and description, since that is where circe's `null` and
    * jsoniter's omission would part ways.
    */
  private val genVideo: Gen[NextVideo] =
    for
      url      <- Gen.alphaNumStr
      duration <- Gen.chooseNum(1, 600)
      title    <- Gen.option(Gen.alphaNumStr)
      descr    <- Gen.option(Gen.alphaNumStr)
    yield NextVideo(url, duration, title, descr)

  /** The four payload types get no case of their own, and that is deliberate. They never appear as
    * a JSON object: the unions below flatten their fields into the top level, written by hand. A
    * standalone case would have checked a derived codec that production does not call — which is
    * how the four of them turned out to be dead code in the first place.
    */
  val tests = Tests:
    test("SurveyResponse (tag merged into the payload)"):
      checkProp(
        parity[SurveyResponse](
          Gen.oneOf(
            genIdentification.map(SurveyResponse.Survey.apply),
            genStep.map(SurveyResponse.Step.apply))))

    test("AdvertiserSurveyResponse"):
      checkProp(
        parity[AdvertiserSurveyResponse](
          Gen.oneOf(
            genAdvertiser.map(AdvertiserSurveyResponse.Survey.apply),
            genStep.map(AdvertiserSurveyResponse.Step.apply))))

    test("VideoResponse"):
      checkProp(
        parity[VideoResponse](
          Gen.oneOf(
            genVideo.map(VideoResponse.Video.apply),
            genStep.map(VideoResponse.Step.apply))))

    test("StatusResponse"):
      checkProp(
        parity(
          for
            phase  <- genPhase
            locale <- Gen.oneOf("es", "en")
            url    <- Gen.option(Gen.alphaNumStr)
            secs   <- Gen.option(Gen.chooseNum(0, 3600))
          yield StatusResponse(phase, locale, url, secs)))
end JsoniterEndpointParityTests
