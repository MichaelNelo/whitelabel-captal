package whitelabel.captal.e2e

import zio.*

/** Where the steps' effects run. A step is synchronous, so each one runs its effect to the end on
  * the one runtime the whole run shares.
  */
object Run:
  def apply[A](effect: ZIO[Any, Throwable, A]): A = Unsafe.unsafe: unsafe ?=>
    Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()

  /** What lives as long as the run: the backoffice's connection and its HTTP client. */
  lazy val scope: Scope.Closeable = apply(Scope.make)

  /** A variable `mill e2e.cucumber` sets. */
  def setting(name: String): String = sys
    .env
    .getOrElse(
      name,
      throw IllegalStateException(s"$name is not set: run the features with `mill e2e.cucumber`"))
end Run
