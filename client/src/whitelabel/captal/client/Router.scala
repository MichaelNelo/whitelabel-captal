package whitelabel.captal.client

import com.raquo.laminar.api.L.*
import com.raquo.waypoint.*
import whitelabel.captal.client.views.{AdvertiserVideoView, IdentificationQuestionView, ReadyView, WelcomeView}
import whitelabel.captal.core.application.Phase

// Pages for the router
sealed trait Page
case object WelcomePage                extends Page
case object IdentificationQuestionPage extends Page
case object AdvertiserVideoPage        extends Page
case object ReadyPage                  extends Page

object Router:
  // Route patterns
  private val welcomeRoute: Route[WelcomePage.type, Unit] = Route.static(
    WelcomePage,
    root / endOfSegments)

  private val questionRoute: Route[IdentificationQuestionPage.type, Unit] = Route.static(
    IdentificationQuestionPage,
    root / "question" / endOfSegments)

  private val advertiserVideoRoute: Route[AdvertiserVideoPage.type, Unit] = Route.static(
    AdvertiserVideoPage,
    root / "video" / endOfSegments)

  private val readyRoute: Route[ReadyPage.type, Unit] = Route.static(
    ReadyPage,
    root / "ready" / endOfSegments)

  private object router
      extends com.raquo.waypoint.Router[Page](
        routes = List(welcomeRoute, questionRoute, advertiserVideoRoute, readyRoute),
        getPageTitle = {
          case WelcomePage =>
            "WiFi Gratis"
          case IdentificationQuestionPage =>
            "Pregunta"
          case AdvertiserVideoPage =>
            "Video"
          case ReadyPage =>
            "Listo"
        },
        serializePage = {
          case WelcomePage =>
            "welcome"
          case IdentificationQuestionPage =>
            "question"
          case AdvertiserVideoPage =>
            "video"
          case ReadyPage =>
            "ready"
        },
        deserializePage = {
          case "welcome" =>
            WelcomePage
          case "question" =>
            IdentificationQuestionPage
          case "video" =>
            AdvertiserVideoPage
          case "ready" =>
            ReadyPage
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

  private def phaseToPage(phase: Phase): Page =
    phase match
      case Phase.Welcome =>
        WelcomePage
      case Phase.IdentificationQuestion =>
        IdentificationQuestionPage
      case Phase.AdvertiserVideo =>
        AdvertiserVideoPage
      case Phase.AdvertiserVideoSurvey =>
        AdvertiserVideoPage // Survey after video, redirect to video for now
      case Phase.AdvertiserQuestion =>
        AdvertiserVideoPage // For now, redirect to video
      case Phase.Ready =>
        ReadyPage

  // SplitRender for efficient view switching
  val splitter: SplitRender[Page, HtmlElement] =
    SplitRender[Page, HtmlElement](router.currentPageSignal)
      .collectStatic(WelcomePage)(WelcomeView.render)
      .collectStatic(IdentificationQuestionPage)(IdentificationQuestionView.render)
      .collectStatic(AdvertiserVideoPage)(AdvertiserVideoView.render)
      .collectStatic(ReadyPage)(ReadyView.render)
end Router
