package whitelabel.captal.core.application.queries

/** A run of a deploy, pause, resume or destroy: by its id, or a location's latest. */
enum RunStatusQuery:
  case ById(run: String)
  case LatestOf(slug: String)

/** One of a run's steps as the operator reads it: pending, running, done or failed, with what it
  * found or why it failed.
  */
final case class StepReport(name: String, state: String, detail: Option[String])

/** Where a run stands. `status` is running, succeeded or failed — or interrupted, for one started
  * whose heartbeat went stale: the process doing the work stopped before it finished.
  */
final case class RunReport(
    id: String,
    slug: String,
    operation: String,
    status: String,
    steps: List[StepReport],
    startedBy: String,
    startedAt: String,
    updatedAt: String,
    finishedAt: Option[String],
    error: Option[String])

/** The objects a location has in S3, of them its uploaded videos: what a destroy asks about. */
final case class AssetsOfQuery(slug: String)

final case class AssetCount(objects: Int, videos: Int)
