package whitelabel.captal.client

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import zio.ZIO

@main
def main(): Unit =
  val appContainer = dom.document.getElementById("app")
  val loader = Option(dom.document.querySelector(".initial-loader"))

  // Fade out initial loader, then mount Laminar
  loader match
    case Some(el) =>
      el.classList.add("fade-out")
      dom
        .window
        .setTimeout(
          () =>
            el.remove()
            render(appContainer, App.app)
            // Check phase after mount and redirect if needed
            App.syncPhaseOnLoad()
          ,
          300 // Match --transition-slow
        )
    case None =>
      render(appContainer, App.app)
      App.syncPhaseOnLoad()
end main

object App:
  val app: HtmlElement = div(cls := "app-root", child <-- Router.splitter.signal)

  /** Check the server-side phase and redirect if the current URL doesn't match. This ensures users
    * entering via direct URLs (e.g., /question, /final) are redirected to the correct phase.
    */
  def syncPhaseOnLoad(): Unit = Runtime.run:
    for
      statusResult <- ApiClient.getStatus()
      _            <- ZIO.succeed:
        statusResult match
          case Right(status) =>
            // Sync i18n locale
            i18n.I18nClient.setLocale(status.locale)
            // Redirect to correct phase
            Router.syncWithPhase(status.phase)
          case Left(_) =>
            // No session yet, stay where we are (will be handled by WelcomeView)
            ()
    yield ()
end App
