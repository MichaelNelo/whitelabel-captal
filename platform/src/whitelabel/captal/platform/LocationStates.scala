package whitelabel.captal.platform

import java.time.Instant

import io.getquill.*
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import zio.*

/** How `locations.state` writes a location's state, [[whitelabel.captal.core.location.State]]. */
object LocationStates:
  val Created = "created"
  val Deployed = "deployed"
  val Paused = "paused"
  val Destroyed = "destroyed"

  /** Records that the run `by` left the location `slug` in `state`: what the backoffice's operation
    * tools write once they succeed, until operations are commands of their own.
    */
  def record(quill: QuillSqlite, slug: String, state: String, by: String): Task[Unit] =
    import quill.*
    val now = Instant.now.toString
    run(
      query[LocationRow]
        .filter(_.slug == lift(slug))
        .update(_.state -> lift(state), _.stateRun -> lift(Option(by)), _.updatedAt -> lift(now)))
      .flatMap(affected =>
        ZIO.when(affected != 1)(
          ZIO.fail(IllegalStateException(s"Recording '$slug' as $state affected $affected rows"))))
      .unit
end LocationStates
