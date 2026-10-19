package whitelabel.captal.client.views
import com.raquo.laminar.api.L.*
import whitelabel.captal.client.Promises.*
import whitelabel.captal.client.i18n.I18nClient
import whitelabel.captal.client.{AppState, BuildInfo, ErrorHandler, ReadyApi, Router, Runtime}
import whitelabel.captal.core.application.Phase

object ReadyView:
  private val isResetting: Var[Boolean] = Var(false)

  /** Triggered on mount. On UniFi success the response carries `phase = Authorized` and the router
    * switches to WelcomeView's countdown; otherwise phase stays Ready and the view remains on
    * `/ready`, where a reload re-mounts and retries. Server-side `findReadyUser` is idempotent.
    *
    * IMPORTANT: do NOT toggle `AppState.setNavigating` here — it unmounts and re-mounts this view,
    * firing `onMountCallback` again in an endless `/api/finish` loop.
    */
  private def callFinish(): Unit = Runtime.run:
    ReadyApi
      .finish()
      .map:
        case Right(status) =>
          AppState.setPhase(status.phase)
          AppState.setSecondsRemaining(status.secondsRemaining)
          Router.syncWithPhase(status.phase)
        case Left(err) =>
          ErrorHandler.escalate(err)

  private def resetPhase(): Unit =
    isResetting.set(true)
    AppState.setNavigating(true)
    Runtime.run:
      ReadyApi
        .resetPhase()
        .map: result =>
          result match
            case Right(_) =>
              Router.syncWithPhase(Phase.Welcome)
            case Left(err) =>
              ErrorHandler.escalate(err)
          isResetting.set(false)

  def render: HtmlElement = Layout(
    content = div(
      cls := "ready-view",
      // Title sits in normal flow right below the brand icon, matching the other views.
      h1(
        cls       := "ready-title",
        styleAttr := "animation-delay: 1000ms",
        child.text <-- I18nClient.i18n.map(_.ready.title)),
      // Spinner + subtitle stacked, centered on the remaining viewport. The spinner stays mounted
      // for ReadyView's whole life; on /api/finish success the router renders Welcome/Authorized.
      div(
        cls := "ready-loader-wrapper",
        div(cls := "spinner spinner--large"),
        p(
          cls       := "ready-subtitle",
          styleAttr := "animation-delay: 1200ms",
          child.text <-- I18nClient.i18n.map(_.ready.subtitle))
      ),
      onMountCallback { _ =>
        callFinish()
      }
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
            onClick --> { _ =>
              resetPhase()
            }
          ))
      else
        div()
  )
end ReadyView
