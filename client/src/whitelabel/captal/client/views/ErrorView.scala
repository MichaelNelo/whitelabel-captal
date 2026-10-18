package whitelabel.captal.client.views
import scala.concurrent.duration.DurationInt
import scala.scalajs.js

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import whitelabel.captal.client.Promises.*
import whitelabel.captal.client.i18n.I18nClient
import whitelabel.captal.client.{AppState, ErrorHandler, Router, Runtime, StatusApi, WelcomeApi}
import whitelabel.captal.core.i18n.I18n
import whitelabel.captal.messages.ApiError

/** Centralized error page. Shown when an unexpected API error occurs (boot status fail, mid-flow
  * endpoint fail, video load fail, async runtime crash). All copy comes from `i18n.error.{title,
  * generic, retry}` so it adapts to the active locale.
  *
  * Validation errors do NOT route here — they stay inline as `.validation-error` in the question
  * views.
  */
object ErrorView:
  def render: HtmlElement =
    // True for one tick, so Layout mounts in loading-state and slides the brand icon from the
    // centre — where the nav-loader left it — to its final position, the same transition as
    // Welcome and Survey. Without it, arriving mid-flow with i18n already loaded, Layout would
    // appear straight in loaded-state and the ring and icon would jump from centre to top with no
    // animation. Combined with `!I18nClient.isLoaded` for the boot-error path: a missing locale
    // holds loading until `ensureLocaleLoaded` finishes, and Layout then transitions exactly once.
    val justMounted: Var[Boolean] = Var(true)
    Layout(
      isLoading = justMounted
        .signal
        .combineWith(I18nClient.isLoaded)
        .map: (just, loaded) =>
          just || !loaded,
      content = div(
        cls := "error-view",
        onMountCallback: _ =>
          ensureLocaleLoaded()
          // A macrotask, not a microtask and not rAF: both run *before* the next paint, so the
          // toggle to `loaded-state` would overwrite `loading-state` without the browser having
          // painted the first one — and the CSS transitions never start, because no class change
          // was ever painted. `setTimeout(0)` puts the callback on the task queue, which drains
          // after the paint: one frame of loading, the class change, and the next frame starts the
          // animation.
          js.timers.setTimeout(0.milliseconds)(justMounted.set(false))
        ,
        div(
          cls := "error-content",
          div(cls := "error-icon", "⚠"),
          h1(cls  := "error-title", child.text <-- I18nClient.i18n.map(_.error.title)),
          p(
            cls := "error-message",
            child.text <--
              AppState
                .error
                .combineWith(I18nClient.i18n)
                .map: (err, i18n) =>
                  messageFor(err, i18n))
        )
      ),
      footer = div(
        button(
          cls := "welcome-button",
          child.text <-- I18nClient.i18n.map(_.error.retry),
          onClick --> { _ =>
            retry()
          }))
    )
  end render

  /** All API errors map to `error.generic` for now. Future iterations can switch on ApiError
    * subtypes to distinguish (e.g. `error.network`, `error.unavailable`).
    */
  private def messageFor(error: Option[ApiError], i18n: I18n): String = i18n.error.generic

  /** Boot fails before the client ever knows a locale — status resolves the locale from the session
    * and drives `I18nClient.setLocale`. Landing on ErrorView with `isLoaded == false` means every
    * copy binding renders as an empty string. Query `/api/locales` and pick the first as a best
    * effort so the user at least sees a title, message and retry button; on failure we stay with
    * the localeVar default ("es") and eat the blank page rather than crash further.
    */
  private def ensureLocaleLoaded(): Unit =
    if !I18nClient.isLoadedNow then
      Runtime.run:
        WelcomeApi
          .getLocales()
          .map:
            case Right(head :: _) =>
              I18nClient.setLocale(head)
            case _ =>
              ()

  /** Retry: clear the error, re-run the boot status check, let the router sync to whatever phase
    * the server reports. If status fails again, ErrorHandler.escalate kicks in and we stay on this
    * page with the new error.
    */
  private def retry(): Unit =
    AppState.clearError()
    AppState.setNavigating(true)
    val headers = parseCaptivePortalHeaders()
    Runtime.run:
      StatusApi
        .getStatus(headers)
        .map:
          case Right(status) =>
            I18nClient.setLocale(status.locale)
            Router.syncWithPhase(status.phase)
          case Left(err) =>
            ErrorHandler.escalate(err)

  private def parseCaptivePortalHeaders(): Map[String, String] =
    val params = new dom.URLSearchParams(dom.window.location.search)
    List(
      "id"       -> "X-Client-Mac",
      "ap"       -> "X-Ap-Mac",
      "url"      -> "X-Redirect-Url",
      "ssid"     -> "X-Ssid",
      "click_id" -> "X-Click-Id")
      .flatMap: (urlParam, headerName) =>
        Option(params.get(urlParam)).filter(_.nonEmpty).map(headerName -> _)
      .toMap
end ErrorView
