package whitelabel.captal.client

import scala.scalajs.js

/** `map` and `flatMap` on `js.Promise`, so call sites and for-comprehensions read exactly as they
  * did with `Future`.
  *
  * The browser already hands us promises — `dom.fetch` returns one — and wrapping them in
  * `scala.concurrent.Future` brings an executor, `CopyOnWriteArrayList` and `AtomicReference` along
  * for a runtime that has one thread. What it buys back is Scala's syntax, and that is what these
  * fifteen lines provide instead.
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
