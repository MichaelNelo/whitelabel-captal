package whitelabel.captal.client

import scala.scalajs.js

import com.raquo.airstream.core.AirstreamError
import io.circe.Json
import io.circe.syntax.*
import org.scalajs.dom
import whitelabel.captal.core.recovery.Frame
import whitelabel.captal.messages.{ClientErrorReport, ErrorFrame}

/** The way out of an error: every path here ends at `error.html`, which asks the server for it.
  *
  * A page of its own rather than a view, because it has to work when this bundle is what failed: it
  * is static, carries its own script (`client/error.html.template`), and learns why it was opened
  * from its URL fragment.
  */
object Recovery:
  private var leaving = false

  /** The server answered with an error it recorded: the way out opens at once. */
  def serverError(errorId: String): Unit = leave(Json.obj("serverErrorId" -> errorId.asJson))

  /** An exception in this bundle's code, reported for the server to judge. */
  def clientError(failure: Any): Unit = leave(Json.obj("report" -> reportOf(failure).asJson))

  /** Part of the bundle did not arrive. Nothing to report: the page offers to load it again. */
  def cannotLoad(): Unit = leave(Json.obj("retry" -> true.asJson))

  /** Errors nothing else catches, sent to [[clientError]] when they come from this origin's code:
    * an extension's error, or one typed into the console, does not interrupt the flow.
    */
  def install(): Unit =
    dom
      .window
      .addEventListener(
        "error",
        (event: dom.ErrorEvent) =>
          val error = event.asInstanceOf[js.Dynamic].error.asInstanceOf[Any]
          if error != null && isOurs(error) then
            clientError(error))
    dom
      .window
      .addEventListener(
        "unhandledrejection",
        (event: dom.Event) =>
          val reason = event.asInstanceOf[js.Dynamic].reason.asInstanceOf[Any]
          if reason != null && isOurs(reason) then
            clientError(reason)
      )
    // An exception inside an observer — a view's event handler, a signal's mapping — goes to
    // Airstream, which only logs it.
    AirstreamError.registerUnhandledErrorCallback(clientError)
  end install

  private def leave(reason: Json): Unit =
    if !leaving then
      leaving = true
      val fragment = js.URIUtils.encodeURIComponent(reason.noSpaces)
      // `replace`: going back would return to the page that failed.
      dom.window.location.replace(s"error.html${dom.window.location.search}#$fragment")

  private def reportOf(failure: Any): ClientErrorReport =
    val (name, message, stack) = describe(failure)
    ClientErrorReport(
      BuildInfo.version,
      name,
      message,
      Frame.fromStack(stack).map(f => ErrorFrame(f.file, f.line, f.column)))

  /** Name, message and stack. A Scala exception is named by its class, which every engine reports
    * alike; Scala.js makes it a JavaScript `Error`, so it has the engine's stack too.
    */
  private def describe(failure: Any): (String, String, String) =
    unwrap(failure) match
      case t: Throwable =>
        (t.getClass.getName, Option(t.getMessage).getOrElse(""), stackOf(t))
      case e: js.Error =>
        (e.name, e.message, stackOf(e))
      case other =>
        ("Error", String.valueOf(other), "")

  private def unwrap(failure: Any): Any =
    failure match
      case js.JavaScriptException(inner) =>
        unwrap(inner)
      case AirstreamError.ObserverError(inner) =>
        unwrap(inner)
      case other =>
        other

  private def stackOf(error: Any): String =
    val stack = error.asInstanceOf[js.Dynamic].stack
    if js.typeOf(stack) == "string" then
      stack.asInstanceOf[String]
    else
      ""

  private def isOurs(failure: Any): Boolean = stackOf(unwrap(failure)).contains(
    dom.window.location.origin)
end Recovery
