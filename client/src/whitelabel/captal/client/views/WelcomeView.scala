package whitelabel.captal.client.views
import scala.scalajs.js

import com.raquo.laminar.api.L.*
import whitelabel.captal.client.Promises.*
import whitelabel.captal.client.i18n.I18nClient
import whitelabel.captal.client.{AppState, ErrorHandler, Router, Runtime, StatusApi, WelcomeApi}
import whitelabel.captal.core.application.Phase
import whitelabel.captal.messages.SurveyResponse

object WelcomeView:
  private val isStarting: Var[Boolean] = Var(false)
  private val availableLocales: Var[List[String]] = Var(List("es", "en"))

  def render: HtmlElement = Layout(
    isLoading = I18nClient.isLoaded.map(!_),
    content = div(
      child <--
        AppState
          .phase
          .combineWith(AppState.secondsRemaining)
          .map:
            case (Some(Phase.Authorized), Some(secondsRemaining)) =>
              authorizedContent(secondsRemaining)
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

  /** Authorized view: counts down the seconds the server reported and, when they run out, asks
    * `/api/status` once, which by then carries the Welcome phase.
    *
    * No absolute time is used anywhere: the counter is seeded from `secondsRemaining`, decremented
    * locally, and the deadline is a single `setTimeout`, so the device clock never enters it.
    */
  private def authorizedContent(remainingSeconds: Int): HtmlElement =
    val remaining: Var[Int] = Var(remainingSeconds)
    // Ticks only repaint the label — they never decide when access ends.
    val tickStream = EventStream.periodic(1000).startWith(0)
    div(
      cls := "welcome-view",
      // Title sits in normal flow right below the brand icon, as in every other view; the card is
      // centered on the remaining viewport by the wrapper below.
      h1(cls := "welcome-title", child.text <-- I18nClient.i18n.map(_.welcome.authorized.title)),
      div(
        cls := "welcome-countdown-wrapper",
        div(
          cls := "welcome-countdown-card",
          p(cls := "welcome-countdown", child.text <-- remaining.signal.map(formatRemaining)),
          p(
            cls := "welcome-countdown-label",
            child.text <-- I18nClient.i18n.map(_.welcome.authorized.remaining))
        )
      ),
      tickStream --> { _ =>
        remaining.update(s => math.max(0, s - 1))
      },
      onMountCallback(_ => scheduleExpiry(remainingSeconds))
    )
  end authorizedContent

  private def authorizedFooter: HtmlElement = div()

  /** `setTimeout` stores its delay in a signed 32-bit int; anything above this overflows and fires
    * immediately, so the delay is clamped.
    */
  private val MaxTimeoutMillis: Double = 2147483647d

  /** The `* 1000` must stay in `Double`: `Int` overflows at 24 days of remaining access and `Long`
    * pulls Scala.js's 64-bit emulation into the bundle.
    */
  private def scheduleExpiry(secondsRemaining: Int): Unit =
    val delayMillis = math.min(math.max(0, secondsRemaining).toDouble * 1000d, MaxTimeoutMillis)
    js.timers.setTimeout(delayMillis)(refreshStatusAfterExpiration())

  private def refreshStatusAfterExpiration(): Unit = Runtime.run:
    StatusApi
      .getStatus()
      .map:
        case Right(status) =>
          AppState.setSecondsRemaining(status.secondsRemaining)
          AppState.setPhase(status.phase)
          Router.syncWithPhase(status.phase)
          // Fired early (imprecise timer): re-arm with the value the server just reported.
          if status.phase == Phase.Authorized then
            status.secondsRemaining.foreach(scheduleExpiry)
        case Left(err) =>
          ErrorHandler.escalate(err)

  /** `mm:ss`, padded by hand: the `f` interpolator would pull `java.util.Formatter` into the
    * bundle.
    */
  private def formatRemaining(seconds: Int): String =
    def twoDigits(n: Int): String =
      if n < 10 then
        "0" + n.toString
      else
        n.toString
    twoDigits(seconds / 60) + ":" + twoDigits(seconds % 60)

  private def step(textSignal: Signal[String], index: Int): HtmlElement = div(
    cls       := "step",
    styleAttr := s"animation-delay: ${index * 0.1}s",
    span(cls := "step-text", child.text <-- textSignal))

  /** Fetch the list of available locales for the dropdown. Locale and phase sync happen at boot in
    * `Main.syncPhaseOnLoad`, not here.
    */
  private def loadLocales(): Unit = Runtime.run:
    WelcomeApi
      .getLocales()
      .map:
        case Right(locales) if locales.nonEmpty =>
          availableLocales.set(locales)
        case _ =>
          ()

  private def setLocaleOnServer(locale: String): Unit = Runtime.run:
    WelcomeApi.setLocale(locale)

  private def startFlow(): Unit =
    isStarting.set(true)
    AppState.setNavigating(true)
    Runtime.run:
      for
        _            <- WelcomeApi.setLocale(I18nClient.currentLocale)
        surveyResult <- WelcomeApi.getNextSurvey()
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
        // The nav-loader is deliberately not turned off here: the destination view's Layout does
        // it on mount, once its lazy chunk has come down.
  end startFlow
end WelcomeView
