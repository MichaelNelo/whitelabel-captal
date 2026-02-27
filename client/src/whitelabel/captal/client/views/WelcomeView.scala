package whitelabel.captal.client.views

import com.raquo.laminar.api.L.*
import whitelabel.captal.client.{ApiClient, AppState, Runtime}
import whitelabel.captal.client.i18n.I18n
import zio.*

object WelcomeView:
  private val isLoading: Var[Boolean] = Var(false)
  private val availableLocales: Var[List[String]] = Var(List("es", "en"))

  def render: HtmlElement =
    div(
      cls := "welcome-view",
      div(
        cls := "welcome-content",
        // Language selector
        div(
          cls := "locale-selector",
          child.text <-- I18n.t("welcome.selectLanguage").map(_ + ": "),
          select(
            cls := "locale-select",
            children <-- availableLocales.signal.map: locales =>
              locales.map: loc =>
                option(
                  value := loc,
                  loc.toUpperCase,
                  selected <-- I18n.locale.map(_ == loc)
                ),
            onChange.mapToValue --> { loc =>
              I18n.setLocale(loc)
              setLocaleOnServer(loc)
            }
          )
        ),
        // WiFi icon
        div(
          cls := "welcome-icon",
          wifiIcon
        ),
        // Welcome text
        h1(cls := "welcome-title", child.text <-- I18n.t("welcome.title")),
        p(cls := "welcome-subtitle", child.text <-- I18n.t("welcome.subtitle")),
        // Process steps
        div(
          cls := "welcome-steps",
          step("1", I18n.t("welcome.step.1")),
          step("2", I18n.t("welcome.step.2")),
          step("3", I18n.t("welcome.step.3"))
        ),
        // CTA Button
        button(
          cls := "welcome-button",
          disabled <-- isLoading.signal,
          child.text <-- isLoading.signal.combineWith(I18n.locale).map:
            case (true, _) => I18n.ts("welcome.button.connecting")
            case (false, _) => I18n.ts("welcome.button.start"),
          onClick --> { _ => startFlow() }
        )
      ),
      onMountCallback { _ => loadLocales() }
    )

  private def step(number: String, textSignal: Signal[String]): HtmlElement =
    div(
      cls := "step",
      span(cls := "step-number", number),
      span(cls := "step-text", child.text <-- textSignal)
    )

  private def wifiIcon: SvgElement =
    svg.svg(
      svg.viewBox := "0 0 24 24",
      svg.cls := "wifi-icon",
      svg.path(svg.d := "M12 18c1.1 0 2 .9 2 2s-.9 2-2 2-2-.9-2-2 .9-2 2-2zm-4.9-2.3l1.4 1.4C9.4 18 10.6 18.5 12 18.5s2.6-.5 3.5-1.4l1.4-1.4c-1.3-1.3-3-2.1-4.9-2.1s-3.6.8-4.9 2.1zm-2.8-2.8l1.4 1.4c2-2 4.7-3.3 7.6-3.3s5.6 1.3 7.6 3.3l1.4-1.4c-2.4-2.4-5.6-3.9-9-3.9s-6.6 1.5-9 3.9zm-2.8-2.8l1.4 1.4C6 8.4 8.9 6.5 12 6.5s6 1.9 9.1 5l1.4-1.4C19 6.6 15.6 4.5 12 4.5S5 6.6 1.5 10.1z")
    )

  private def loadLocales(): Unit =
    Runtime.run:
      ApiClient.getLocales().tap:
        case Right(locales) if locales.nonEmpty =>
          ZIO.succeed(availableLocales.set(locales))
        case _ =>
          ZIO.unit

  private def setLocaleOnServer(locale: String): Unit =
    Runtime.run:
      ApiClient.setLocale(locale).ignore

  private def startFlow(): Unit =
    isLoading.set(true)
    Runtime.run:
      for
        _ <- ApiClient.setLocale(I18n.currentLocale)
        result <- ApiClient.getStatus()
        _ <- ZIO.succeed:
          result match
            case Right(status) =>
              AppState.setLocale(status.locale)
              AppState.setPhase(status.phase)
            case Left(_) =>
              ()
          isLoading.set(false)
      yield ()
