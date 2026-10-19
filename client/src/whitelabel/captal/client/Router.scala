package whitelabel.captal.client

import scala.scalajs.js

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

/** Closed hierarchy of SPA routes. */
enum Page:
  case Welcome
  case IdentificationQuestion
  case AdvertiserVideo
  case AdvertiserVideoSurvey
  case Ready
  case Error

object Router:
  /** Slug-aware base path: `/<slug>` in production, empty in dev where the SPA runs at `/`. */
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

  /** Sync router with server-side phase. Must skip `pushState` when already on the target page:
    * Waypoint's `currentPageSignal` re-emits on every pushState, remounting the cached view.
    */
  def syncWithPhase(phase: Phase): Unit =
    val targetPage = phaseToPage(phase)
    if router.currentPageSignal.now() != targetPage then
      router.pushState(targetPage)

  /** Navigate to the centralized error page. Sits outside the phase machine; called from
    * `ErrorHandler.escalate`.
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
      case Phase.Authorized =>
        Page.Welcome // WelcomeView detects Authorized phase + countdown

  /** Wraps one route's view in the stable element slot `collectStatic` requires. The `route-slot`
    * class carries `display: contents` in styles.css, keeping the wrapper out of the layout tree.
    *
    * `js.dynamicImport` must be written at the `collectStatic` call site, not inside this helper:
    * the split point is where the expression appears, and a closure built outside leaves the view
    * reachable from the main module. `collectStatic` takes its argument by name, so the import
    * begins when the route is first rendered rather than at startup.
    */
  private def lazily(view: => js.Promise[HtmlElement]): HtmlElement = div(
    cls := "route-slot",
    child <-- EventStream.fromJsPromise(view, emitOnce = true))

  val splitter: SplitRender[Page, HtmlElement] =
    SplitRender[Page, HtmlElement](router.currentPageSignal)
      .collectStatic(Page.Welcome)(lazily(js.dynamicImport(WelcomeView.render)))
      .collectStatic(Page.IdentificationQuestion)(
        lazily(js.dynamicImport(IdentificationQuestionView.render)))
      .collectStatic(Page.AdvertiserVideo)(lazily(js.dynamicImport(AdvertiserVideoView.render)))
      .collectStatic(Page.AdvertiserVideoSurvey)(
        lazily(js.dynamicImport(AdvertiserVideoSurveyView.render)))
      .collectStatic(Page.Ready)(lazily(js.dynamicImport(ReadyView.render)))
      .collectStatic(Page.Error)(lazily(js.dynamicImport(ErrorView.render)))
end Router
