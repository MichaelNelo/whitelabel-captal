package whitelabel.captal.client

import scala.scalajs.js

import org.scalajs.dom
import whitelabel.captal.messages.StatusResponse

/** Failures on demand, for the end-to-end features to recover from.
  *
  * Only in a bundle built for the local stack: `BuildInfo.faults` is a constant, so every other
  * bundle carries none of this. And only where the location's API mounts its dev endpoints, which
  * answer whether a failure may be made: a bundle with this hook, served where they are not, makes
  * none.
  *
  * A test dispatches `captal-fault` on the window, its `detail` the kind:
  *   - `server`: the API fails for real and records it, and its answer escalates as any call's
  *     would, which opens the way out of an error the server recorded.
  *   - `client` or `client:<tag>`: the API says it may, and this bundle's own code throws, as a bug
  *     of ours would. The tag is in the error's name, which the server tells errors apart by: one
  *     tag is one error, however many devices throw it, and another tag is another.
  */
object Faults:
  def install(): Unit =
    if BuildInfo.faults then
      dom
        .window
        .addEventListener(
          "captal-fault",
          (event: dom.CustomEvent) => inject(String.valueOf(event.detail)))

  private def inject(detail: String): Unit =
    val (kind, tag) =
      detail.split(":", 2) match
        case Array(kind, tag) =>
          (kind, Some(tag))
        case _ =>
          (detail, None)
    val _ = ApiClient
      .post[StatusResponse](ApiClient.url(s"/dev/fault/$kind"))
      .`then`[Unit]: result =>
        result match
          case Left(error) =>
            ErrorHandler.escalate(error)
          case Right(_) =>
            val error = js.Error("A client error, injected through captal-fault")
            // Writable in JavaScript; the facade declares it read-only.
            error.asInstanceOf[js.Dynamic].name =
              tag.fold("InjectedFault")(tag => s"InjectedFault $tag")
            // From a timer, so it reaches the window as an uncaught error does, which is how a bug in
            // a view's code reaches it.
            val _ =
              js.timers
                .setTimeout(0):
                  throw js.JavaScriptException(error)
  end inject
end Faults
