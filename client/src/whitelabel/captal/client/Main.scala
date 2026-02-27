package whitelabel.captal.client

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import whitelabel.captal.client.views.WelcomeView

@main def main(): Unit =
  renderOnDomContentLoaded(
    dom.document.getElementById("app"),
    App.app
  )

object App:
  val app: HtmlElement = WelcomeView.render
