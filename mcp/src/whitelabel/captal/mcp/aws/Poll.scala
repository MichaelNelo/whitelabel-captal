package whitelabel.captal.mcp.aws

import zio.*

/** Asking AWS the same question until the answer settles, with a pause between asks. Without the
  * pause a wait of minutes is thousands of describe calls, and the throttling error that brings
  * back fails the operation that was only waiting.
  */
private[aws] object Poll:
  val Every: Duration = 5.seconds

  /** Runs `check` now and then every [[Every]] until `done` holds, for at most `within`. `None`
    * when time ran out first.
    */
  def until[A](check: ZIO[Any, String, A], within: Duration)(
      done: A => Boolean): ZIO[Any, String, Option[A]] = check
    .repeat(Schedule.spaced(Every) *> Schedule.recurUntil(done))
    .timeout(within)
end Poll
