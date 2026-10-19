package whitelabel.captal.client

import scala.scalajs.js

import org.scalajs.dom
import whitelabel.captal.client.Promises.*

object Runtime:
  /** Run a promise, ignoring the result. Failures are logged to the console and escalated to the
    * error page.
    */
  def run[A](body: => js.Promise[A]): Unit =
    val _ = body.onFailure: e =>
      dom.console.error(s"Async failure: $e", e.toString)
      ErrorHandler.escalateMessage(s"Unexpected: $e")
