package whitelabel.captal.platform.operations

import java.time.Instant

import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Encoder}
import whitelabel.captal.core.location.{LocationId, Slug}
import whitelabel.captal.core.operation.{Operation, Run, RunId, RunState, Step, StepFailure}
import whitelabel.captal.platform.RunRow

/** How `mcp_deploy_runs` holds a run: its status, and its steps as JSON, each by the description
  * the operator reads — which is also how a step is known again when the run is read back.
  */
object RunRecords:
  val Running = "running"
  val Succeeded = "succeeded"
  val Failed = "failed"

  /** Never stored: what a `running` run is reported as once its heartbeat went stale. */
  val Interrupted = "interrupted"

  val StepPending = "pending"
  val StepRunning = "running"
  val StepDone = "done"
  val StepFailed = "failed"

  final case class StepEntry(name: String, state: String, detail: Option[String])

  private given Encoder[StepEntry] = deriveEncoder
  private given Decoder[StepEntry] = deriveDecoder

  def steps(row: RunRow): List[StepEntry] = decode[List[StepEntry]](row.steps).getOrElse(Nil)

  def encode(steps: List[StepEntry]): String = steps.asJson.noSpaces

  /** The steps a run starts with, all of them pending. */
  def pending(steps: List[Step]): String = encode(
    steps.map(step => StepEntry(step.description, StepPending, None)))

  private def stepOf(entry: StepEntry): Option[Step] = Step.values.find(_.description == entry.name)

  /** The run as the model has it, at the location `location`. A destroy's own name does not say
    * whether it deletes the objects; its steps do.
    */
  def runOf(row: RunRow, location: LocationId): Run[RunState] =
    val entries = steps(row)
    val operation =
      row.operation match
        case "deploy" =>
          Operation.Deploy
        case "pause" =>
          Operation.Pause
        case "resume" =>
          Operation.Resume
        case _ =>
          Operation.Destroy(entries.exists(_.name == Step.DeleteAssets.description))
    val state: RunState =
      row.status match
        case Running =>
          RunState.Started(Instant.parse(row.heartbeatAt))
        case Succeeded =>
          RunState.Completed(Instant.parse(row.finishedAt.getOrElse(row.heartbeatAt)))
        case _ =>
          RunState.Failed(
            entries
              .find(_.state == StepFailed)
              .flatMap(stepOf)
              .orElse(entries.find(_.state != StepDone).flatMap(stepOf))
              .getOrElse(Step.values.head),
            StepFailure.Rejected(row.error.getOrElse("Failed."))
          )
    Run(
      RunId(row.id),
      location,
      Slug.unsafe(row.slug),
      operation,
      entries.filter(_.state != StepDone).flatMap(stepOf),
      row.startedBy,
      state)
  end runOf
end RunRecords
