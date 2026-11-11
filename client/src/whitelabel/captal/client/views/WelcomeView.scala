package whitelabel.captal.client.views
import scala.scalajs.js

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L.*
import org.scalajs.dom
import whitelabel.captal.client.Promises.*
import whitelabel.captal.client.i18n.I18nClient
import whitelabel.captal.client.{AppState, ErrorHandler, Router, Runtime, StatusApi, WelcomeApi}
import whitelabel.captal.core.application.{NextStep, Phase}
import whitelabel.captal.messages.{ApiError, SurveyResponse, TermsResponse}

object WelcomeView:
  private val isStarting: Var[Boolean] = Var(false)

  /** The terms of service on screen, while their dialog is open. */
  private val openedTerms: Var[Option[TermsResponse]] = Var(None)

  /** Whether the guest declined them last: the welcome says they have to be accepted. */
  private val termsDeclined: Var[Boolean] = Var(false)
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

  private def welcomeContent: HtmlElement =
    // Takes the open dialog off `document.body`; nothing while none is open.
    var closeTerms: () => Unit = () => ()
    div(
      cls := "welcome-view",
      h1(cls := "welcome-title", child.text <-- I18nClient.i18n.map(_.welcome.title)),
      p(cls  := "welcome-subtitle", child.text <-- I18nClient.i18n.map(_.welcome.subtitle)),
      div(
        cls := "welcome-steps",
        step(I18nClient.i18n.map(_.welcome.steps.step1), 1),
        step(I18nClient.i18n.map(_.welcome.steps.step2), 2),
        step(I18nClient.i18n.map(_.welcome.steps.step3), 3)
      ),
      child.maybe <--
        termsDeclined
          .signal
          .map(declined =>
            Option.when(declined)(
              p(
                cls := "terms-required",
                child.text <-- I18nClient.i18n.map(_.welcome.terms.required)))),
      // The dialog is mounted on `document.body`, not in this view: `.view-content`'s transform
      // makes `position: fixed` relative to it, so the overlay would cover that box alone, inside
      // its stacking context, under the footer and its locale picker.
      openedTerms.signal --> { opened =>
        closeTerms()
        closeTerms = () => ()
        opened.foreach: terms =>
          val container = dom.document.createElement("div")
          val _ = dom.document.body.appendChild(container)
          // `L.render`: this view's own `render` shadows Laminar's here.
          val root = L.render(container, termsDialog(terms))
          closeTerms =
            () =>
              val _ = root.unmount()
              val _ = dom.document.body.removeChild(container)
      },
      onMountCallback { _ =>
        loadLocales()
      },
      onUnmountCallback(_ => closeTerms())
    )
  end welcomeContent

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
          p(
            cls := "welcome-countdown",
            children <--
              remaining
                .signal
                .map(seconds =>
                  remainingParts(seconds).flatMap((value, unit) =>
                    List(span(value), span(cls := "welcome-countdown-unit", unit))))
          ),
          p(
            cls := "welcome-countdown-label",
            child.text <--
              I18nClient.i18n.map(i18n => remainingLabel(i18n.welcome.authorized.remaining)))
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

  /** The time left as numbers with their units, the largest first — `1 h 05 min 12 s`,
    * `59 min 16 s`, `16 s` — since a bare `59:16` does not say whether it is minutes or hours. Each
    * unit is its own element, smaller than its number, which keeps the line short enough to fit.
    * `h`, `min` and `s` are the international symbols, written the same in Spanish and English, so
    * they need no translation. Padded by hand: the `f` interpolator would pull
    * `java.util.Formatter` into the bundle.
    */
  private def remainingParts(seconds: Int): List[(String, String)] =
    def twoDigits(n: Int): String =
      if n < 10 then
        "0" + n.toString
      else
        n.toString
    val hours = seconds / 3600
    val minutes = seconds / 60 % 60
    val secs = seconds         % 60
    if hours > 0 then
      List(hours.toString -> "h", twoDigits(minutes) -> "min", twoDigits(secs) -> "s")
    else if minutes > 0 then
      List(minutes.toString -> "min", twoDigits(secs) -> "s")
    else
      List(secs.toString -> "s")

  /** The label under the countdown, which shows the time itself: a `{seconds}` in the translation,
    * in any case, is dropped with the colon before it.
    */
  private def remainingLabel(text: String): String =
    val placeholder = "{seconds}"
    text.toLowerCase.indexOf(placeholder) match
      case -1 =>
        text
      case at =>
        (text.take(at).trim.stripSuffix(":").trim + " " + text.drop(at + placeholder.length).trim)
          .trim

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

  /** Leaves the welcome: at once for a device that accepted the terms of service in force, through
    * their dialog for one that has to.
    */
  private def startFlow(): Unit =
    if AppState.currentPhase.contains(Phase.WelcomeNeedsToS) then
      openTerms()
    else
      leave(WelcomeApi.start())

  /** Opens the dialog with the terms in the session's locale, set first so they are the ones the
    * guest picked.
    */
  private def openTerms(): Unit =
    isStarting.set(true)
    Runtime.run:
      for
        _     <- WelcomeApi.setLocale(I18nClient.currentLocale)
        terms <- WelcomeApi.getTerms()
      yield
        isStarting.set(false)
        terms match
          case Right(read) =>
            termsDeclined.set(false)
            openedTerms.set(Some(read))
          case Left(err) =>
            ErrorHandler.escalate(err)

  /** Runs the operation that leaves the welcome, and goes where the step it answers leads: for the
    * identification questions, with the first one already fetched.
    */
  private def leave(operation: => js.Promise[Either[ApiError, NextStep]]): Unit =
    isStarting.set(true)
    openedTerms.set(None)
    AppState.setNavigating(true)
    Runtime.run:
      for
        _    <- WelcomeApi.setLocale(I18nClient.currentLocale)
        step <- operation
        next <-
          step match
            case Right(NextStep(Phase.IdentificationQuestion)) =>
              WelcomeApi.getNextSurvey()
            case Right(other) =>
              js.Promise.resolve(Right(SurveyResponse.Step(other)))
            case Left(err) =>
              js.Promise.resolve(Left(err))
      yield
        next match
          case Right(SurveyResponse.Survey(survey)) =>
            AppState.setCurrentSurvey(survey)
            AppState.setPhase(Phase.IdentificationQuestion)
            Router.syncWithPhase(Phase.IdentificationQuestion)
          case Right(SurveyResponse.Step(nextStep)) =>
            AppState.setPhase(nextStep.phase)
            Router.syncWithPhase(nextStep.phase)
          case Left(ApiError.TermsNotAccepted) =>
            // The terms changed since this page asked: they have to be accepted again.
            AppState.setNavigating(false)
            AppState.setPhase(Phase.WelcomeNeedsToS)
            openTerms()
          case Left(err) =>
            ErrorHandler.escalate(err)
        isStarting.set(false)
        // The nav-loader is deliberately not turned off here: the destination view's Layout does
        // it on mount, once its lazy chunk has come down.
  end leave

  /** The terms of service in force, over the welcome, until the guest accepts or declines them.
    * Declining closes it and leaves a notice: nothing begins without them.
    */
  private def termsDialog(terms: TermsResponse): HtmlElement = div(
    cls := "terms-overlay",
    div(
      cls  := "terms-dialog",
      role := "dialog",
      h2(cls  := "terms-title", child.text <-- I18nClient.i18n.map(_.welcome.terms.title)),
      div(cls := "terms-text", terms.text),
      div(
        cls := "terms-actions",
        button(
          cls := "terms-decline",
          child.text <-- I18nClient.i18n.map(_.welcome.terms.decline),
          onClick --> { _ =>
            openedTerms.set(None)
            termsDeclined.set(true)
          }),
        button(
          cls := "terms-accept",
          disabled <-- isStarting.signal,
          child.text <-- I18nClient.i18n.map(_.welcome.terms.accept),
          onClick --> { _ =>
            leave(WelcomeApi.acceptTerms())
          }
        )
      )
    )
  )
end WelcomeView
