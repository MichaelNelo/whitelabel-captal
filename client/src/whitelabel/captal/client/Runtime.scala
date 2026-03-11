package whitelabel.captal.client

import org.scalajs.dom
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object Runtime:
  /** Run a Future, ignoring the result. Errors are logged to console. */
  def run[A](body: => Future[A]): Unit =
    body.failed.foreach(e => dom.console.error(s"Error: ${e.getMessage}"))
