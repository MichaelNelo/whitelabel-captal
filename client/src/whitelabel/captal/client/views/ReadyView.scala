package whitelabel.captal.client.views

import com.raquo.laminar.api.L.*
import whitelabel.captal.client.{ApiClient, BuildInfo, Router, Runtime}
import whitelabel.captal.core.application.Phase
import zio.ZIO

object ReadyView:
  private val isResetting: Var[Boolean] = Var(false)

  private def resetPhase(): Unit =
    isResetting.set(true)
    Runtime.run:
      for
        result <- ApiClient.resetPhase()
        _      <- ZIO.succeed:
          result match
            case Right(_) =>
              Router.syncWithPhase(Phase.Welcome)
            case Left(_) =>
              ()
          isResetting.set(false)
      yield ()

  def render: HtmlElement = Layout(
    content = div(
      cls := "ready-view",
      h1(cls := "ready-title", styleAttr    := "animation-delay: 300ms", "Gracias!"),
      p(cls  := "ready-subtitle", styleAttr := "animation-delay: 500ms", "Ya puedes navegar")
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
                .map(
                  if _ then
                    "..."
                  else
                    "Reset (Dev)"),
            onClick --> { _ =>
              resetPhase()
            }
          ))
      else
        div()
  )
end ReadyView
