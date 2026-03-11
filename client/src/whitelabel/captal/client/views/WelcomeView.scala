package whitelabel.captal.client.views

import com.raquo.laminar.api.L.*
import whitelabel.captal.client.i18n.I18nClient
import whitelabel.captal.client.{ApiClient, AppState, Router, Runtime}
import whitelabel.captal.core.application.Phase
import whitelabel.captal.endpoints.SurveyResponse
import scala.concurrent.ExecutionContext.Implicits.global

object WelcomeView:
  private val isStarting: Var[Boolean] = Var(false)
  private val availableLocales: Var[List[String]] = Var(List("es", "en"))

  def render: HtmlElement = Layout(
    isLoading = I18nClient.isLoaded.map(!_),
    content = div(
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
    ),
    footer = div(
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
  )

  private def step(textSignal: Signal[String], index: Int): HtmlElement = div(
    cls       := "step",
    styleAttr := s"animation-delay: ${index * 0.1}s",
    span(cls := "step-text", child.text <-- textSignal))

  private def loadLocales(): Unit = Runtime.run:
    for
      statusResult <- ApiClient.getStatus()
      locale =
        statusResult match
          case Right(status) =>
            if status.phase != Phase.Welcome then
              Router.syncWithPhase(status.phase)
            status.locale
          case Left(_) =>
            detectBrowserLocale()
      _ = I18nClient.setLocale(locale)
      localesResult <- ApiClient.getLocales()
    yield
      localesResult match
        case Right(locales) if locales.nonEmpty =>
          availableLocales.set(locales)
        case _ =>
          ()

  private def detectBrowserLocale(): String =
    import org.scalajs.dom
    val browserLang = dom.window.navigator.language
    if browserLang.startsWith("es") then
      "es"
    else if browserLang.startsWith("en") then
      "en"
    else
      "es" // default

  private def setLocaleOnServer(locale: String): Unit = Runtime.run:
    ApiClient.setLocale(locale)

  private def startFlow(): Unit =
    isStarting.set(true)
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
          case Left(_) =>
            ()
        isStarting.set(false)
  end startFlow
end WelcomeView
