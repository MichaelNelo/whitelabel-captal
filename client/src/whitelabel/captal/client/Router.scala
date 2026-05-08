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

// Pages for the router
sealed trait Page
case object WelcomePage                extends Page
case object IdentificationQuestionPage extends Page
case object AdvertiserVideoPage        extends Page
case object AdvertiserVideoSurveyPage  extends Page
case object ReadyPage                  extends Page
case object ErrorPage                  extends Page

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
  private val welcomeRoute: Route[WelcomePage.type, Unit] = Route.static(
    WelcomePage,
    root / endOfSegments,
    basePath = basePath)

  private val questionRoute: Route[IdentificationQuestionPage.type, Unit] = Route.static(
    IdentificationQuestionPage,
    root / "question" / endOfSegments,
    basePath = basePath)

  private val advertiserVideoRoute: Route[AdvertiserVideoPage.type, Unit] = Route.static(
    AdvertiserVideoPage,
    root / "video" / endOfSegments,
    basePath = basePath)

  private val advertiserVideoSurveyRoute: Route[AdvertiserVideoSurveyPage.type, Unit] = Route
    .static(AdvertiserVideoSurveyPage, root / "survey" / endOfSegments, basePath = basePath)

  private val readyRoute: Route[ReadyPage.type, Unit] = Route.static(
    ReadyPage,
    root / "ready" / endOfSegments,
    basePath = basePath)

  private val errorRoute: Route[ErrorPage.type, Unit] = Route.static(
    ErrorPage,
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
          case WelcomePage =>
            "WiFi Gratis"
          case IdentificationQuestionPage =>
            "Pregunta"
          case AdvertiserVideoPage =>
            "Video"
          case AdvertiserVideoSurveyPage =>
            "Encuesta"
          case ReadyPage =>
            "Listo"
          case ErrorPage =>
            "Error"
        },
        serializePage = {
          case WelcomePage =>
            "welcome"
          case IdentificationQuestionPage =>
            "question"
          case AdvertiserVideoPage =>
            "video"
          case AdvertiserVideoSurveyPage =>
            "survey"
          case ReadyPage =>
            "ready"
          case ErrorPage =>
            "error"
        },
        deserializePage = {
          case "welcome" =>
            WelcomePage
          case "question" =>
            IdentificationQuestionPage
          case "video" =>
            AdvertiserVideoPage
          case "survey" =>
            AdvertiserVideoSurveyPage
          case "ready" =>
            ReadyPage
          case "error" =>
            ErrorPage
          case _ =>
            WelcomePage
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
  def navigateToError(): Unit = router.pushState(ErrorPage)

  private def phaseToPage(phase: Phase): Page =
    phase match
      case Phase.Welcome =>
        WelcomePage
      case Phase.IdentificationQuestion =>
        IdentificationQuestionPage
      case Phase.AdvertiserVideo =>
        AdvertiserVideoPage
      case Phase.AdvertiserVideoSurvey =>
        AdvertiserVideoSurveyPage
      case Phase.AdvertiserQuestion =>
        AdvertiserVideoSurveyPage
      case Phase.Ready =>
        ReadyPage

  // SplitRender for efficient view switching
  val splitter: SplitRender[Page, HtmlElement] =
    SplitRender[Page, HtmlElement](router.currentPageSignal)
      .collectStatic(WelcomePage)(WelcomeView.render)
      .collectStatic(IdentificationQuestionPage)(IdentificationQuestionView.render)
      .collectStatic(AdvertiserVideoPage)(AdvertiserVideoView.render)
      .collectStatic(AdvertiserVideoSurveyPage)(AdvertiserVideoSurveyView.render)
      .collectStatic(ReadyPage)(ReadyView.render)
      .collectStatic(ErrorPage)(ErrorView.render)
end Router
