package whitelabel.captal.client

import scala.scalajs.js

/** `map` and `flatMap` on `js.Promise`, so call sites and for-comprehensions read as they do with
  * `Future`.
  */
object Promises:
  extension [A](promise: js.Promise[A])
    def map[B](f: A => B): js.Promise[B] = promise.`then`[B]((a: A) => f(a): B | js.Thenable[B])

    def flatMap[B](f: A => js.Promise[B]): js.Promise[B] = promise.`then`[B]((a: A) =>
      f(a): B | js.Thenable[B])

    /** Rejections carry `Any`, not `Throwable`: a JS promise can be rejected with anything. */
    def onFailure(f: Any => Unit): js.Promise[Unit] = promise
      .`then`[Unit]((_: A) => (): Unit | js.Thenable[Unit])
      .`catch`((e: Any) => f(e): Unit | js.Thenable[Unit])
