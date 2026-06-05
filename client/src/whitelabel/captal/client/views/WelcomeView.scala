package whitelabel.captal.client.views

import java.time.Instant

import scala.concurrent.ExecutionContext.Implicits.global

import com.raquo.laminar.api.L.*
import whitelabel.captal.client.i18n.I18nClient
import whitelabel.captal.client.{ApiClient, AppState, ErrorHandler, Router, Runtime}
import whitelabel.captal.core.application.Phase
import whitelabel.captal.endpoints.SurveyResponse

object WelcomeView:
  private val isStarting: Var[Boolean] = Var(false)
  private val availableLocales: Var[List[String]] = Var(List("es", "en"))

  def render: HtmlElement = Layout(
    isLoading = I18nClient.isLoaded.map(!_),
    content = div(
      child <--
        AppState
          .phase
          .combineWith(AppState.accessExpiresAt)
          .map:
            case (Some(Phase.Authorized), Some(expiresAt)) =>
              authorizedContent(expiresAt)
            case _ =>
              welcomeContent),
    footer = div(
      child <--
        AppState
          .phase
          .map:
            case Some(Phase.Authorized) =>
              authorizedFooter
            case _ =>
              welcomeFooter)
  )

  private def welcomeContent: HtmlElement = div(
    cls := "welcome-view",
    h1(cls := "welcome-title", child.text <-- I18nClient.i18n.map(_.welcome.title)),
    p(cls  := "welcome-subtitle", child.text <-- I18nClient.i18n.map(_.welcome.subtitle)),
    div(
      cls := "welcome-steps",
      step(I18nClient.i18n.map(_.welcome.steps.step1), 1),
      step(I18nClient.i18n.map(_.welcome.steps.step2), 2),
      step(I18nClient.i18n.map(_.welcome.steps.step3), 3)
    ),
    onMountCallback { _ =>
      loadLocales()
    }
  )

  private def welcomeFooter: HtmlElement = div(
    select(
      cls := "locale-button",
      children <--
        availableLocales
          .signal
          .map: locales =>
            locales.map: loc =>
              option(value := loc, loc.toUpperCase, selected <-- I18nClient.locale.map(_ == loc)),
      onChange.mapToValue --> { loc =>
        I18nClient.setLocale(loc)
        setLocaleOnServer(loc)
      }
    ),
    button(
      cls := "welcome-button",
      disabled <-- isStarting.signal,
      child.text <--
        isStarting
          .signal
          .combineWith(I18nClient.i18n)
          .map:
            case (true, i18n) =>
              i18n.welcome.button.connecting
            case (false, i18n) =>
              i18n.welcome.button.start,
      onClick --> { _ =>
        startFlow()
      }
    )
  )

  /** Authorized view: shows a countdown until `expiresAt`. Once the deadline is reached, polls
    * `/api/status`; the server resets the session and we render the normal Welcome again.
    */
  private def authorizedContent(expiresAt: Instant): HtmlElement =
    // Tick every second to refresh the countdown label.
    val tickStream = EventStream.periodic(1000).startWith(0L)
    div(
      cls := "welcome-view",
      // Title sits in normal flow right below the brand icon, matching the layout of every
      // other view (Welcome, Ready, etc.). The card itself is centered on the remaining
      // viewport via the wrapper below.
      h1(cls := "welcome-title", child.text <-- I18nClient.i18n.map(_.welcome.authorized.title)),
      div(
        cls := "welcome-countdown-wrapper",
        div(
          cls := "welcome-countdown-card",
          p(
            cls := "welcome-countdown",
            child.text <--
              tickStream.map: _ =>
                formatRemaining(expiresAt)),
          p(
            cls := "welcome-countdown-label",
            child.text <-- I18nClient.i18n.map(_.welcome.authorized.remaining))
        )
      ),
      tickStream --> { _ =>
        if java.time.Instant.now().isAfter(expiresAt) then
          refreshStatusAfterExpiration()
      }
    )

  private def authorizedFooter: HtmlElement = div()

  private def formatRemaining(expiresAt: Instant): String =
    val now = java.time.Instant.now()
    val seconds = math.max(0L, java.time.Duration.between(now, expiresAt).getSeconds)
    val minutes = seconds / 60
    val secs = seconds % 60
    f"$minutes%02d:$secs%02d"

  private def refreshStatusAfterExpiration(): Unit = Runtime.run:
    ApiClient
      .getStatus()
      .map:
        case Right(status) =>
          AppState.setAccessExpiresAt(status.accessExpiresAt)
          AppState.setPhase(status.phase)
          Router.syncWithPhase(status.phase)
        case Left(err) =>
          ErrorHandler.escalate(err)

  private def step(textSignal: Signal[String], index: Int): HtmlElement = div(
    cls       := "step",
    styleAttr := s"animation-delay: ${index * 0.1}s",
    span(cls := "step-text", child.text <-- textSignal))

  /** Fetch the list of available locales for the dropdown. The session-creation `/api/status` call
    * already happened once at boot in `Main.syncPhaseOnLoad` — locale + phase sync live there. This
    * view only needs the locales list.
    */
  private def loadLocales(): Unit = Runtime.run:
    ApiClient
      .getLocales()
      .map:
        case Right(locales) if locales.nonEmpty =>
          availableLocales.set(locales)
        case _ =>
          ()

  private def setLocaleOnServer(locale: String): Unit = Runtime.run:
    ApiClient.setLocale(locale)

  private def startFlow(): Unit =
    isStarting.set(true)
    AppState.setNavigating(true)
    Runtime.run:
      for
        _            <- ApiClient.setLocale(I18nClient.currentLocale)
        surveyResult <- ApiClient.getNextSurvey()
      yield
        surveyResult match
          case Right(SurveyResponse.Survey(survey)) =>
            AppState.setCurrentSurvey(survey)
            AppState.setPhase(Phase.IdentificationQuestion)
            Router.syncWithPhase(Phase.IdentificationQuestion)
          case Right(SurveyResponse.Step(nextStep)) =>
            AppState.setPhase(nextStep.phase)
            Router.syncWithPhase(nextStep.phase)
          case Left(err) =>
            ErrorHandler.escalate(err)
        isStarting.set(false)
        AppState.setNavigating(false)
  end startFlow
end WelcomeView
