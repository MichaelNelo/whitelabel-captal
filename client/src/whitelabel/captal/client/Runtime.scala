package whitelabel.captal.client

import zio.*

object Runtime:
  private val runtime: zio.Runtime[Any] = zio.Runtime.default

  /** Run a ZIO effect, ignoring the result. Errors are logged to console. */
  def run[E, A](effect: ZIO[Any, E, A]): Unit =
    Unsafe.unsafe { (u: Unsafe) =>
      given Unsafe = u
      runtime.unsafe
        .runToFuture(effect.catchAll(e => ZIO.succeed(org.scalajs.dom.console.error(s"ZIO error: $e"))))
      ()
    }
