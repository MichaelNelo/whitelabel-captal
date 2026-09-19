package whitelabel.captal.client.views
import scala.scalajs.js

import com.raquo.laminar.api.L.*
import whitelabel.captal.client.Promises.*
import whitelabel.captal.client.i18n.I18nClient
import whitelabel.captal.client.{AppState, ErrorHandler, Router, Runtime, StatusApi, WelcomeApi}
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
    * `/api/status` once — by then the scheduler has already reset the session server-side, so the
    * response carries the Welcome phase and the router re-renders.
    *
    * No absolute time is used anywhere: the counter is seeded from `secondsRemaining` and
    * decremented locally, and the deadline is a single `setTimeout`. The device clock never enters
    * the picture, which matters because a captive-portal client has had no internet yet and may not
    * have synced NTP.
    *
    * Losing the timer is harmless. If the tab is closed or discarded, `Main.syncPhaseOnLoad` calls
    * `/api/status` on the next load and gets the right phase; if it fires late (background
    * throttling, suspended device) the answer is the same. If it somehow fires early, the response
    * still says Authorized and the countdown is simply re-seeded.
    */
  private def authorizedContent(remainingSeconds: Int): HtmlElement =
    val remaining: Var[Int] = Var(remainingSeconds)
    // Ticks only repaint the label — they never decide when access ends.
    val tickStream = EventStream.periodic(1000).startWith(0)
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

  /** The multiplication happens in `Double` rather than `Int`, which would overflow at 24 days of
    * remaining access — and in `Long`, which is what this used to be and what pulls Scala.js's
    * 64-bit emulation into the bundle. `setTimeout` takes a Double anyway.
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

  /** Written out rather than `f"$minutes%02d:$secs%02d"`, because the `f` interpolator goes through
    * `java.util.Formatter` — a general-purpose format-string engine that measured at 7.6 KB of the
    * bundle, to pad two numbers to two digits.
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

  /** Fetch the list of available locales for the dropdown. The session-creation `/api/status` call
    * already happened once at boot in `Main.syncPhaseOnLoad` — locale + phase sync live there. This
    * view only needs the locales list.
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
        AppState.setNavigating(false)
  end startFlow
end WelcomeView
