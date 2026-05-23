package whitelabel.captal.client.views

import scala.concurrent.ExecutionContext.Implicits.global

import com.raquo.laminar.api.L.*
import whitelabel.captal.client.i18n.I18nClient
import whitelabel.captal.client.{ApiClient, AppState, BuildInfo, ErrorHandler, Router, Runtime}
import whitelabel.captal.core.application.Phase

object ReadyView:
  private val isResetting: Var[Boolean] = Var(false)

  /** Triggered on every mount. If UniFi succeeded → response has phase=Authorized and a defined
    * accessExpiresAt; the router will switch to WelcomeView in Authorized mode (countdown). If
    * UniFi failed or no config → phase stays Ready, accessExpiresAt = None and we remain on
    * /ready (a reload re-mounts → retries). Server-side `findReadyUser` is idempotent.
    */
  private def callFinish(): Unit =
    AppState.setNavigating(true)
    Runtime.run:
      ApiClient.finish().map:
        case Right(status) =>
          AppState.setPhase(status.phase)
          AppState.setAccessExpiresAt(status.accessExpiresAt)
          Router.syncWithPhase(status.phase)
          AppState.setNavigating(false)
        case Left(err) =>
          AppState.setNavigating(false)
          ErrorHandler.escalate(err)

  private def resetPhase(): Unit =
    isResetting.set(true)
    AppState.setNavigating(true)
    Runtime.run:
      ApiClient
        .resetPhase()
        .map: result =>
          result match
            case Right(_) =>
              Router.syncWithPhase(Phase.Welcome)
            case Left(err) =>
              ErrorHandler.escalate(err)
          isResetting.set(false)
          AppState.setNavigating(false)

  def render: HtmlElement = Layout(
    content = div(
      cls := "ready-view",
      h1(
        cls       := "ready-title",
        styleAttr := "animation-delay: 1000ms",
        child.text <-- I18nClient.i18n.map(_.ready.title)),
      p(
        cls       := "ready-subtitle",
        styleAttr := "animation-delay: 1200ms",
        child.text <-- I18nClient.i18n.map(_.ready.subtitle)),
      onMountCallback { _ => callFinish() }
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
                  if resetting then
                    "..."
                  else
                    resetText
            ,
            onClick --> { _ => resetPhase() }
          ))
      else
        div()
  )
end ReadyView
