package whitelabel.captal.mcp.aws

import java.time.Instant
import java.util.UUID

import io.getquill.*
import whitelabel.captal.mcp.{QuillArrow, Writes}
import whitelabel.captal.platform.schema.QuillSqlite
import zio.*
import zio.schema.codec.JsonCodec as SchemaJsonCodec
import zio.schema.{DeriveSchema, Schema}

import QuillArrow.->

/** Deploys, pauses, resumes and destroys of a location's portal, run in the background and recorded
  * step by step in `mcp_deploy_runs`.
  *
  * They wait minutes on AWS, longer than a host keeps one tool call open, so the tool that starts
  * one answers with the run's id at once. The row is everything a reader needs: it outlives the
  * call that started the run and the conversation it was started from.
  */
object Runs:
  inline given SchemaMeta[Row] = schemaMeta[Row]("mcp_deploy_runs")

  final case class Row(
      id: String,
      slug: String,
      operation: String,
      status: String,
      steps: String,
      result: Option[String],
      error: Option[String],
      startedBy: String,
      startedAt: String,
      heartbeatAt: String,
      finishedAt: Option[String])

  final case class Step(name: String, state: String, detail: Option[String])

  given Schema[Step] = DeriveSchema.gen

  val Running = "running"
  val Succeeded = "succeeded"
  val Failed = "failed"

  /** Never stored: what a `running` row is reported as once its heartbeat has stopped. */
  val Interrupted = "interrupted"

  val StepPending = "pending"
  val StepRunning = "running"
  val StepDone = "done"
  val StepFailed = "failed"

  private val HeartbeatEvery: Duration = 10.seconds

  /** A heartbeat older than this means the process doing the work is gone. */
  private val StaleAfter: Duration = 1.minute

  private val stepsEncoder = SchemaJsonCodec.jsonEncoder(summon[Schema[List[Step]]])
  private val stepsDecoder = SchemaJsonCodec.jsonDecoder(summon[Schema[List[Step]]])

  private def encode(steps: List[Step]): String = stepsEncoder.encodeJson(steps, None).toString

  def steps(row: Row): List[Step] = stepsDecoder.decodeJson(row.steps).getOrElse(Nil)

  /** The row's status as a reader should see it: `running` turns into [[Interrupted]] once nothing
    * has kept its heartbeat going.
    */
  def statusOf(row: Row, now: Instant): String =
    val stale = Instant.parse(row.heartbeatAt).plus(StaleAfter).isBefore(now)
    if row.status == Running && stale then
      Interrupted
    else
      row.status

  private inline def byId = quote: (idParam: String) =>
    query[Row].filter(_.id == idParam)

  private inline def bySlug = quote: (slugParam: String) =>
    query[Row].filter(_.slug == slugParam)

  def get(id: String): ZIO[QuillSqlite, Nothing, Option[Row]] = ZIO.serviceWithZIO[QuillSqlite]:
    quill =>
      import quill.*
      run(byId(lift(id))).orDie.map(_.headOption)

  def latest(slug: String): ZIO[QuillSqlite, Nothing, Option[Row]] = ZIO.serviceWithZIO[
    QuillSqlite]: quill =>
    import quill.*
    run(bySlug(lift(slug))).orDie.map(_.maxByOption(_.startedAt))

  /** The run still going for a slug: recorded as running, with a heartbeat recent enough that its
    * work is alive.
    */
  def active(slug: String): ZIO[QuillSqlite, Nothing, Option[Row]] =
    for
      rows <- ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        run(bySlug(lift(slug))).orDie
      now <- Clock.instant
    yield rows.find(row => statusOf(row, now) == Running)

  /** Records a run and starts `work` on a fiber of its own, detached from the call that asked for
    * it, beside a heartbeat that lasts exactly as long as the work does. `work` answers the
    * operation's result as JSON.
    *
    * `Left` with the run already going when one is, and then nothing is started: two operations on
    * the same location would scale and repoint the same service against each other.
    */
  def launch[R](slug: String, operation: String, stepNames: List[String], startedBy: String)(
      work: Progress => ZIO[R, String, String]): ZIO[R & QuillSqlite, String, Either[Row, Row]] =
    active(slug).flatMap:
      case Some(going) =>
        ZIO.succeed(Left(going))
      case None =>
        for
          now <- Clock.instant.map(_.toString)
          row = Row(
            id = UUID.randomUUID.toString,
            slug = slug,
            operation = operation,
            status = Running,
            steps = encode(stepNames.map(Step(_, StepPending, None))),
            result = None,
            error = None,
            startedBy = startedBy,
            startedAt = now,
            heartbeatAt = now,
            finishedAt = None
          )
          _ <- ZIO.serviceWithZIO[QuillSqlite]: quill =>
            import quill.*
            run(query[Row].insertValue(lift(row)))
              .orDie
              .flatMap(Writes.expectOne(_, s"Recording the $operation of '$slug'"))
          env <- ZIO.environment[R & QuillSqlite]
          _   <-
            ZIO
              .scoped(
                heartbeat(row.id).repeat(Schedule.spaced(HeartbeatEvery)).forkScoped *>
                  work(Progress(row.id)))
              .foldCauseZIO(
                cause => finish(row.id, Failed, None, Some(describe(cause))),
                result => finish(row.id, Succeeded, Some(result), None))
              .provideEnvironment(env)
              .forkDaemon
        yield Right(row)

  /** The operation's own words when it failed with them, and the defect's otherwise. */
  private def describe(cause: Cause[String]): String = cause
    .failureOption
    .orElse(cause.dieOption.map(defect => Option(defect.getMessage).getOrElse(defect.toString)))
    .getOrElse(
      if cause.isInterruptedOnly then
        "Interrupted before it finished."
      else
        cause.prettyPrint.linesIterator.nextOption().getOrElse("Failed."))

  private def heartbeat(id: String): ZIO[QuillSqlite, Nothing, Unit] = Clock
    .instant
    .flatMap: now =>
      ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        run(byId(lift(id)).update(_.heartbeatAt -> lift(now.toString))).orDie.unit

  /** Best effort: a run whose end cannot be written stays `running` until its heartbeat goes stale
    * and readers report it as interrupted, which is the same advice.
    */
  private def finish(
      id: String,
      status: String,
      result: Option[String],
      error: Option[String]): ZIO[QuillSqlite, Nothing, Unit] = Clock
    .instant
    .flatMap: now =>
      ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        run(
          byId(lift(id)).update(
            _.status      -> lift(status),
            _.result      -> lift(result),
            _.error       -> lift(error),
            _.heartbeatAt -> lift(now.toString),
            _.finishedAt  -> lift(Option(now.toString))))
          .orDie
          .flatMap(Writes.expectOne(_, s"Finishing run $id"))
    .catchAllCause(cause => ZIO.logErrorCause(s"Could not record the end of run $id", cause))

  /** What a run's work reports through. One writer per run, so rewriting the whole step list cannot
    * lose another writer's update.
    */
  final class Progress private[Runs] (val runId: String):
    /** Marks `name` running, runs `effect`, then marks it done or failed. */
    def step[R, A](name: String)(effect: ZIO[R, String, A]): ZIO[R & QuillSqlite, String, A] =
      noting(name)(effect)(_ => None)

    /** As [[step]], with a detail drawn from the result once it is done. */
    def noting[R, A](name: String)(effect: ZIO[R, String, A])(
        detail: A => Option[String]): ZIO[R & QuillSqlite, String, A] =
      mark(name, StepRunning, None) *>
        effect
          .tapError(error => mark(name, StepFailed, Some(error)))
          .tap(value => mark(name, StepDone, detail(value)))

    private def mark(
        name: String,
        state: String,
        detail: Option[String]): ZIO[QuillSqlite, Nothing, Unit] =
      for
        row <- get(runId).someOrElseZIO(ZIO.dieMessage(s"Run $runId is not recorded"))
        now <- Clock.instant
        updated = steps(row).map(step =>
          if step.name == name then
            Step(name, state, detail)
          else
            step)
        _ <- ZIO.serviceWithZIO[QuillSqlite]: quill =>
          import quill.*
          run(
            byId(lift(runId)).update(
              _.steps       -> lift(encode(updated)),
              _.heartbeatAt -> lift(now.toString))).orDie
      yield ()
  end Progress
end Runs
