package whitelabel.captal.client.views

import com.raquo.laminar.api.L.*
import whitelabel.captal.client.i18n.I18nClient
import whitelabel.captal.client.{ApiClient, BuildInfo, Router, Runtime}
import whitelabel.captal.core.application.Phase
import scala.concurrent.ExecutionContext.Implicits.global

object ReadyView:
  private val isResetting: Var[Boolean] = Var(false)

  private def resetPhase(): Unit =
    isResetting.set(true)
    Runtime.run:
      ApiClient.resetPhase().map: result =>
        result match
          case Right(_) =>
            Router.syncWithPhase(Phase.Welcome)
          case Left(_) =>
            ()
        isResetting.set(false)

  def render: HtmlElement = Layout(
    content = div(
      cls := "ready-view",
      h1(cls := "ready-title", styleAttr := "animation-delay: 1000ms", child.text <-- I18nClient.i18n.map(_.ready.title)),
      p(cls := "ready-subtitle", styleAttr := "animation-delay: 1200ms", child.text <-- I18nClient.i18n.map(_.ready.subtitle))
    ),
    footer =
      if BuildInfo.isDevMode then
        div(
          button(
            cls := "dev-reset-button",
            disabled <-- isResetting.signal,
            child.text <--
              isResetting
                .signal
                .combineWith(I18nClient.i18n.map(_.ready.resetButton))
                .map: (resetting, resetText) =>
                  if resetting then "..." else resetText,
            onClick --> { _ =>
              resetPhase()
            }
          ))
      else
        div()
  )
end ReadyView
