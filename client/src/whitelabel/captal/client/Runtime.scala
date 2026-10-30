package whitelabel.captal.client

import scala.scalajs.js

import org.scalajs.dom
import whitelabel.captal.client.Promises.*

object Runtime:
  /** Run a promise, ignoring the result. A failure is an exception in this bundle's code — a lost
    * connection arrives as a `Left`, from [[ApiClient.fetchApi]] — so it is logged and reported.
    */
  def run[A](body: => js.Promise[A]): Unit =
    val _ = body.onFailure: e =>
      dom.console.error(s"Async failure: $e", e.toString)
      Recovery.clientError(e)
