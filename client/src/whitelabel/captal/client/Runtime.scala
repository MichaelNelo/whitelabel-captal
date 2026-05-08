package whitelabel.captal.client

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import org.scalajs.dom

object Runtime:
  /** Run a Future, ignoring the result. Errors are logged to console AND escalated to the
    * centralized error page so the user gets feedback instead of a silent failure.
    */
  def run[A](body: => Future[A]): Unit = body
    .failed
    .foreach: e =>
      dom.console.error(s"Async failure: ${e.getMessage}", e.toString)
      ErrorHandler.escalateMessage(s"Unexpected: ${e.getMessage}")
