package whitelabel.captal.client

import com.raquo.laminar.api.L.*
import com.raquo.waypoint.*
import org.scalajs.dom
import whitelabel.captal.client.views.{
  AdvertiserVideoSurveyView,
  AdvertiserVideoView,
  ErrorView,
  IdentificationQuestionView,
  ReadyView,
  WelcomeView
}
import whitelabel.captal.core.application.Phase

/** Closed hierarchy of SPA routes. Scala 3 enum (not `sealed trait`) to match the project
  * convention for finite enumerations.
  */
enum Page:
  case Welcome
  case IdentificationQuestion
  case AdvertiserVideo
  case AdvertiserVideoSurvey
  case Ready
  case Error

object Router:
  /** Slug-aware base path. In production the SPA is served from `/<slug>/index.html`, so all routes
    * need that prefix; in dev the SPA runs at `/`, no prefix.
    */
  private val basePath: String =
    val firstSegment = dom.window.location.pathname.split("/").find(_.nonEmpty)
    firstSegment match
      case Some(slug) if slug != "api" =>
        s"/$slug"
      case _ =>
        ""

  // Route patterns
  private val welcomeRoute: Route[Page.Welcome.type, Unit] = Route.static(
    Page.Welcome,
    root / endOfSegments,
    basePath = basePath)

  private val questionRoute: Route[Page.IdentificationQuestion.type, Unit] = Route.static(
    Page.IdentificationQuestion,
    root / "question" / endOfSegments,
    basePath = basePath)

  private val advertiserVideoRoute: Route[Page.AdvertiserVideo.type, Unit] = Route.static(
    Page.AdvertiserVideo,
    root / "video" / endOfSegments,
    basePath = basePath)

  private val advertiserVideoSurveyRoute: Route[Page.AdvertiserVideoSurvey.type, Unit] = Route
    .static(Page.AdvertiserVideoSurvey, root / "survey" / endOfSegments, basePath = basePath)

  private val readyRoute: Route[Page.Ready.type, Unit] = Route.static(
    Page.Ready,
    root / "ready" / endOfSegments,
    basePath = basePath)

  private val errorRoute: Route[Page.Error.type, Unit] = Route.static(
    Page.Error,
    root / "error" / endOfSegments,
    basePath = basePath)

  private object router
      extends com.raquo.waypoint.Router[Page](
        routes = List(
          welcomeRoute,
          questionRoute,
          advertiserVideoRoute,
          advertiserVideoSurveyRoute,
          readyRoute,
          errorRoute),
        getPageTitle = {
          case Page.Welcome =>
            "WiFi Gratis"
          case Page.IdentificationQuestion =>
            "Pregunta"
          case Page.AdvertiserVideo =>
            "Video"
          case Page.AdvertiserVideoSurvey =>
            "Encuesta"
          case Page.Ready =>
            "Listo"
          case Page.Error =>
            "Error"
        },
        serializePage = {
          case Page.Welcome =>
            "welcome"
          case Page.IdentificationQuestion =>
            "question"
          case Page.AdvertiserVideo =>
            "video"
          case Page.AdvertiserVideoSurvey =>
            "survey"
          case Page.Ready =>
            "ready"
          case Page.Error =>
            "error"
        },
        deserializePage = {
          case "welcome" =>
            Page.Welcome
          case "question" =>
            Page.IdentificationQuestion
          case "video" =>
            Page.AdvertiserVideo
          case "survey" =>
            Page.AdvertiserVideoSurvey
          case "ready" =>
            Page.Ready
          case "error" =>
            Page.Error
          case _ =>
            Page.Welcome
        }
      )

  // Sync router with server-side phase
  def syncWithPhase(phase: Phase): Unit =
    import org.scalajs.dom.console
    val targetPage = phaseToPage(phase)
    console.log(s"syncWithPhase: phase=$phase, targetPage=$targetPage")
    router.pushState(targetPage)
    console.log("pushState done")

  /** Navigate to the centralized error page. Sits outside the phase machine — call this from
    * `ErrorHandler.escalate` when an unexpected API or runtime failure occurs.
    */
  def navigateToError(): Unit = router.pushState(Page.Error)

  private def phaseToPage(phase: Phase): Page =
    phase match
      case Phase.Welcome =>
        Page.Welcome
      case Phase.IdentificationQuestion =>
        Page.IdentificationQuestion
      case Phase.AdvertiserVideo =>
        Page.AdvertiserVideo
      case Phase.AdvertiserVideoSurvey =>
        Page.AdvertiserVideoSurvey
      case Phase.AdvertiserQuestion =>
        Page.AdvertiserVideoSurvey
      case Phase.Ready =>
        Page.Ready

  // SplitRender for efficient view switching
  val splitter: SplitRender[Page, HtmlElement] =
    SplitRender[Page, HtmlElement](router.currentPageSignal)
      .collectStatic(Page.Welcome)(WelcomeView.render)
      .collectStatic(Page.IdentificationQuestion)(IdentificationQuestionView.render)
      .collectStatic(Page.AdvertiserVideo)(AdvertiserVideoView.render)
      .collectStatic(Page.AdvertiserVideoSurvey)(AdvertiserVideoSurveyView.render)
      .collectStatic(Page.Ready)(ReadyView.render)
      .collectStatic(Page.Error)(ErrorView.render)
end Router
