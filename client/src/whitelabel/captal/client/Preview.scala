package whitelabel.captal.client

import scala.scalajs.js

import com.raquo.laminar.api.L.*
import io.circe.generic.semiauto.deriveDecoder
import io.circe.syntax.*
import io.circe.{Decoder, Json, parser}
import org.scalajs.dom
import whitelabel.captal.client.Promises.*
import whitelabel.captal.client.views.{
  AdvertiserVideoSurveyView,
  AdvertiserVideoView,
  ErrorView,
  IdentificationQuestionView,
  ReadyView,
  WelcomeView
}
import whitelabel.captal.core.application.commands.{
  NextAdvertiserSurvey,
  NextIdentificationSurvey,
  NextVideo
}
import whitelabel.captal.core.application.{IdentificationSurveyType, Phase}
import whitelabel.captal.core.i18n.{I18n, I18nSchema}
import whitelabel.captal.core.survey
import whitelabel.captal.core.survey.question.{
  Id as QuestionId,
  LocalizedText,
  OptionId,
  QuestionOption,
  QuestionToAnswer,
  QuestionType
}
import whitelabel.captal.messages.i18n.given
import whitelabel.captal.messages.{
  AdvertiserSurveyResponse,
  PlaybackResponse,
  StatusResponse,
  SurveyResponse,
  VideoResponse
}

/** One screen of the portal as the backoffice's `preview_location` shows it, drawn by the portal's
  * own views with a location's texts, before the location is ever deployed. Started by the preview
  * page (`mcpui.preview.PreviewPortal`) with what the call's result says: the screen, the locale,
  * the texts and the locales. The video screen plays a video made here ([[PreviewVideo]]).
  *
  * Nothing reaches the network. Every call the views make is answered here, in the API's own types:
  * the texts and the locales from the result, the rest made up. A call whose answer would move the
  * guest on is never answered, which keeps the screen where it is. The router is not used: the
  * screen's view is drawn directly.
  */
object Preview:

  enum Screen:
    case Welcome,
      Question,
      Video,
      Survey,
      Ready,
      Access,
      Error

  /** What the preview page passes on from the call's result. Mirrors the screen-specific part of
    * `PreviewTools.PreviewResponse`.
    */
  final private case class Config(
      screen: String,
      locale: String,
      texts: Map[String, String],
      locales: List[String])

  private given Decoder[Config] = deriveDecoder

  private var config = Option.empty[Config]

  /** How long a recording the video screen plays. */
  private val VideoSeconds = 3.0

  /** The video screen's answer to `/video/next`, made by [[PreviewVideo]] once it is first asked
    * for: recording takes as long as the video, during which the view shows its own loader, as it
    * does for a guest whose video is on its way. Where the browser cannot record, it never comes
    * and the screen stays on that loader.
    */
  private lazy val video: js.Promise[Json] = PreviewVideo
    .record(VideoSeconds)
    .map(url => (VideoResponse.Video(nextVideo(url)): VideoResponse).asJson)
    .`catch`[Json](_ => never[Json]: Json | js.Thenable[Json])

  private def never[A]: js.Promise[A] = new js.Promise[A]((_, _) => ())

  private def screen: Option[Screen] = config.flatMap(c =>
    Screen.values.find(_.toString.equalsIgnoreCase(c.screen)))

  private def locale = config.fold("es")(_.locale)

  /** Answers the views in place of the network, then draws the screen `result` names into
    * `container`. `result` is the call's structured result, as JSON. Answers whether there was a
    * screen to draw.
    */
  def start(result: String, container: dom.Element): Boolean =
    config = parser.decode[Config](result).toOption
    screen.foreach: shown =>
      ApiClient.standIn = Some(intercept)
      render(shown, container)
    screen.isDefined

  /** What a guest who has an hour left of access sees counted down. */
  private val AccessSeconds = 59 * 60 + 30

  private def render(shown: Screen, container: dom.Element): Unit =
    if shown == Screen.Access then
      AppState.setPhase(Phase.Authorized)
      AppState.setSecondsRemaining(Some(AccessSeconds))
    i18n.I18nClient.setLocale(locale)
    val view =
      shown match
        case Screen.Welcome | Screen.Access =>
          WelcomeView.render
        case Screen.Question =>
          IdentificationQuestionView.render
        case Screen.Video =>
          AdvertiserVideoView.render
        case Screen.Survey =>
          AdvertiserVideoSurveyView.render
        case Screen.Ready =>
          ReadyView.render
        case Screen.Error =>
          ErrorView.render
    val _ = com
      .raquo
      .laminar
      .api
      .L
      .render(container, div(cls := "app-root", div(cls := "route-slot", view)))
  end render

  /** How the preview answers `method` on `path`, the path under the API's base, or `None` for a
    * call it leaves unanswered. Nothing goes to the network.
    */
  private def intercept(method: String, path: String): Option[js.Promise[Json]] =
    def now(answer: Json) = Some(js.Promise.resolve[Json](answer))
    (method, path) match
      case ("GET", "/locales") =>
        config.flatMap(c => now(c.locales.asJson))
      case ("GET", texts) if texts.startsWith("/i18n/") =>
        config.flatMap(c => now(I18nSchema.decode[I18n](c.texts).asJson))
      case ("GET", "/status") | ("PUT", "/session/locale") =>
        now(status.asJson)
      case ("GET", "/survey/next") =>
        now((SurveyResponse.Survey(identification): SurveyResponse).asJson)
      case ("GET", "/video/next") =>
        Some(video)
      case ("POST", "/video/playback/start") =>
        now(PlaybackResponse("preview").asJson)
      case ("GET", "/survey/advertiser/next") =>
        now((AdvertiserSurveyResponse.Survey(advertiser): AdvertiserSurveyResponse).asJson)
      case _ =>
        None
  end intercept

  private def status = StatusResponse(
    phase = screen.fold(Phase.Welcome)(phaseOf),
    locale = locale,
    secondsRemaining = Option.when(screen.contains(Screen.Access))(AccessSeconds))

  /** The phase a guest is in on `shown`. The error screen is outside the phases, as the portal's
    * error view is: it answers the welcome, where its retry would lead.
    */
  private def phaseOf(shown: Screen): Phase =
    shown match
      case Screen.Welcome | Screen.Error =>
        Phase.Welcome
      case Screen.Question =>
        Phase.IdentificationQuestion
      case Screen.Video =>
        Phase.AdvertiserVideo
      case Screen.Survey =>
        Phase.AdvertiserVideoSurvey
      case Screen.Ready =>
        Phase.Ready
      case Screen.Access =>
        Phase.Authorized

  /** The made-up content, in the locale shown: the location writes its own questions and videos, so
    * none of it is a translation key.
    */
  final private case class Copy(
      question: String,
      options: List[String],
      videoTitle: String,
      videoText: String,
      survey: String,
      placeholder: String)

  private def copy =
    if locale == "en" then
      Copy(
        question = "How often do you come here?",
        options = List("First time", "Once a month", "Every week"),
        videoTitle = "A video from the venue",
        videoText = "Watch it to the end to connect.",
        survey = "What did you think of the video?",
        placeholder = "Your answer"
      )
    else
      Copy(
        question = "¿Con qué frecuencia viene aquí?",
        options = List("Es la primera vez", "Una vez al mes", "Todas las semanas"),
        videoTitle = "Un video del lugar",
        videoText = "Mírelo hasta el final para conectarse.",
        survey = "¿Qué le pareció el video?",
        placeholder = "Su respuesta"
      )

  private def text(value: String) = LocalizedText(value, locale)

  private def nextVideo(url: String): NextVideo = NextVideo(
    videoUrl = url,
    durationSeconds = VideoSeconds.toInt,
    title = Some(copy.videoTitle),
    description = Some(copy.videoText))

  private def identification = NextIdentificationSurvey(
    surveyId = survey.Id.unsafe("00000000-0000-4000-8000-000000000001"),
    surveyType = IdentificationSurveyType.Profiling,
    question = QuestionToAnswer(
      id = QuestionId.unsafe("00000000-0000-4000-8000-000000000002"),
      text = text(copy.question),
      description = None,
      placeholder = None,
      questionType = QuestionType.Radio(
        copy
          .options
          .zipWithIndex
          .map((option, index) =>
            QuestionOption(
              OptionId.unsafe(f"00000000-0000-4000-8000-0000000001$index%02d"),
              text(option),
              index,
              None))),
      commonRules = Nil,
      pointsAwarded = 0
    )
  )

  private def advertiser = NextAdvertiserSurvey(
    surveyId = survey.Id.unsafe("00000000-0000-4000-8000-000000000003"),
    advertiserId = None,
    question = QuestionToAnswer(
      id = QuestionId.unsafe("00000000-0000-4000-8000-000000000004"),
      text = text(copy.survey),
      description = None,
      placeholder = Some(text(copy.placeholder)),
      questionType = QuestionType.Input(Nil),
      commonRules = Nil,
      pointsAwarded = 0
    )
  )
end Preview
