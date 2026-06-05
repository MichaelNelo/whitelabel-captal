package whitelabel.captal.client.views

import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js
import scala.util.Try

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import whitelabel.captal.client.i18n.I18nClient
import whitelabel.captal.client.{ApiClient, AppState, BuildInfo, ErrorHandler, Router, Runtime}
import whitelabel.captal.core.application.Phase

object ReadyView:
  private val isResetting: Var[Boolean] = Var(false)

  /** Triggered on mount. If UniFi succeeded → response has phase=Authorized and a defined
    * accessExpiresAt; the router will switch to WelcomeView in Authorized mode (countdown). If
    * UniFi failed or no config → phase stays Ready, accessExpiresAt = None and we remain on /ready
    * (a reload re-mounts → retries). Server-side `findReadyUser` is idempotent.
    *
    * IMPORTANT: do NOT toggle `AppState.setNavigating` here. Doing so flips the App-level child
    * between navigationLoader and ReadyView, which unmounts then re-mounts this view, firing
    * `onMountCallback` again — producing an infinite `/api/finish` loop whenever UniFi keeps
    * returning Ready (e.g. UCG unreachable). The view's own title/subtitle stay visible while
    * the request is in flight.
    */
  private def callFinish(): Unit =
    Runtime.run:
      ApiClient
        .finish()
        .map:
          case Right(status) =>
            AppState.setPhase(status.phase)
            AppState.setAccessExpiresAt(status.accessExpiresAt)
            // Round-trip canonicalization: the URL may have travelled through CloudFront,
            // an HTTP header, and the DB. If any hop left it with mixed encoding (e.g.
            // `http://host%2Fpath`), `location.assign` rejects it. decodeURIComponent here
            // is the last line of defense before the browser parses the URL.
            val safeRedirect = status
              .redirectUrl
              .map(safeDecodeURIComponent)
              .filter(isSafeRedirect)
            if status.phase == Phase.Authorized && safeRedirect.isDefined then
              dom.window.location.assign(safeRedirect.get)
            else
              Router.syncWithPhase(status.phase)
          case Left(err) =>
            ErrorHandler.escalate(err)

  /** `session.redirect_url` originates from the UniFi captive-portal `url=` query parameter, which
    * is attacker-influenceable (rogue AP could inject `javascript:` or other schemes). Restrict
    * navigation to HTTP(S) absolute URLs so the worst case is a redirect to a benign page.
    */
  private def isSafeRedirect(url: String): Boolean =
    url.startsWith("http://") || url.startsWith("https://")

  private def safeDecodeURIComponent(value: String): String =
    Try(js.URIUtils.decodeURIComponent(value)).getOrElse(value)

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
      // Title sits in normal flow right below the brand icon, matching the other views.
      h1(
        cls       := "ready-title",
        styleAttr := "animation-delay: 1000ms",
        child.text <-- I18nClient.i18n.map(_.ready.title)),
      // Spinner + subtitle stacked, centered on the remaining viewport (mirrors the
      // welcome-countdown-wrapper layout but without a card background). The spinner is
      // always mounted while ReadyView is alive; on /api/finish success the router
      // unmounts (either to redirect away or to Welcome/Authorized).
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
