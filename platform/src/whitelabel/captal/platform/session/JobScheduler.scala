package whitelabel.captal.platform.session

import java.time.Instant

import scala.collection.immutable.SortedMap

import whitelabel.captal.core.application.Flow
import whitelabel.captal.core.application.commands.ExpireAccessCommand
import whitelabel.captal.core.user
import zio.*

/** A job with a deadline. */
enum ScheduledJob:
  case ExpireAccess(sessionId: user.SessionId)

final case class ScheduledAt(deadline: Instant, job: ScheduledJob)

/** Producer side of the scheduler: just the inbound queue. Must stay separate from [[JobRunner]]
  * and dependency-free, or the layer graph goes cyclic — `UnifiAccessHandler` is inside
  * `eventHandlerLayer` and schedules jobs, while the runner depends on `eventHandlerLayer` through
  * its `Flow`.
  */
final class JobQueue private (private[session] val inbox: Queue[ScheduledAt]):
  def schedule(at: ScheduledAt): UIO[Unit] = inbox.offer(at).unit

object JobQueue:
  /** Unbounded: at most one entry per authorized session of this location. */
  val layer: ULayer[JobQueue] = ZLayer.fromZIO(Queue.unbounded[ScheduledAt].map(new JobQueue(_)))

/** Consumer side of the scheduler. */
trait JobRunner:
  /** Forks the scheduler fiber and the worker fiber, and rehydrates pending expirations. Must be
    * called explicitly (`Main.run` does) — building the layer starts nothing. The fibers live for
    * the given `Scope`.
    */
  def start: ZIO[Scope, Nothing, Unit]

object JobRunner:
  private given Ordering[Instant] = Ordering.fromLessThan(_.isBefore(_))

  type ExpireFlow = Flow.Aux[Task, ExpireAccessCommand, Unit]

  val layer: ZLayer[JobQueue & SessionService & SessionContext & ExpireFlow, Nothing, JobRunner] =
    ZLayer.fromZIO:
      for
        jobQueue <- ZIO.service[JobQueue]
        sessions <- ZIO.service[SessionService]
        ctx      <- ZIO.service[SessionContext]
        flow     <- ZIO.service[ExpireFlow]
        runner   <- make(jobQueue, sessions, ctx, flow)
      yield runner

  /** Direct constructor, for a caller that needs its own runner. `start` may be called only once
    * per runner: a second call forks schedulers competing over the same queue.
    */
  def make(
      jobQueue: JobQueue,
      sessions: SessionService,
      ctx: SessionContext,
      flow: ExpireFlow): UIO[JobRunner] =
    for due <- Queue.unbounded[ScheduledJob]
    yield new JobRunner:
      def start: ZIO[Scope, Nothing, Unit] =
        for
          _ <- schedulerLoop(jobQueue.inbox, due, SortedMap.empty).forkScoped
          _ <- workerLoop(due, sessions, ctx, flow).forkScoped
          // Rehydration: the scheduler's state is in memory only. Deadlines already past fire
          // right away (the computed wait is 0).
          pending <- sessions.findPendingExpirations().orDie
          _       <-
            ZIO.foreachDiscard(pending): (sessionId, deadline) =>
              jobQueue.schedule(ScheduledAt(deadline, ScheduledJob.ExpireAccess(sessionId)))
          _ <- ZIO.logInfo(s"JobRunner started, rehydrated ${pending.size} pending expiration(s)")
        yield ()

  /** A single fiber. `inbox.take.timeout(wait)` covers both signals at once: if a job arrives the
    * `take` returns and the queue is reordered; if the timeout elapses, some deadlines came due.
    */
  private def schedulerLoop(
      inbox: Queue[ScheduledAt],
      due: Queue[ScheduledJob],
      pending: SortedMap[Instant, List[ScheduledJob]]): UIO[Unit] =
    for
      now <- Clock.instant
      wait =
        pending.headOption match
          case None =>
            Duration.Infinity
          case Some((at, _)) =>
            // zio.Duration IS java.time.Duration in ZIO 2 — no conversion needed.
            val d = java.time.Duration.between(now, at)
            if d.isNegative then
              Duration.Zero
            else
              d
      arrival <- inbox.take.timeout(wait)
      next    <-
        arrival match
          case Some(ScheduledAt(deadline, job)) =>
            ZIO.succeed(pending.updated(deadline, job :: pending.getOrElse(deadline, Nil)))
          case None =>
            for
              ahora <- Clock.instant
              // O(n), n = authorized sessions of one location.
              (due_, rest) = pending.partition((at, _) => !at.isAfter(ahora))
              _ <- ZIO.foreachDiscard(due_.values.flatten)(due.offer)
            yield rest
      _ <- schedulerLoop(inbox, due, next)
    yield ()

  /** A single fiber → concurrency of 1 against the UCG. `UnifiClient`'s `.timeout` bounds how long
    * one job can hold up the rest of the queue.
    */
  private def workerLoop(
      due: Queue[ScheduledJob],
      sessions: SessionService,
      ctx: SessionContext,
      flow: ExpireFlow): UIO[Unit] = due.take.flatMap(execute(_, sessions, ctx, flow)).forever

  private def execute(
      job: ScheduledJob,
      sessions: SessionService,
      ctx: SessionContext,
      flow: ExpireFlow): UIO[Unit] =
    job match
      case ScheduledJob.ExpireAccess(sessionId) =>
        val effect = sessions
          .findById(sessionId)
          .flatMap:
            case None =>
              ZIO.logDebug(s"Session ${sessionId.asString} no longer exists — job discarded")
            case Some(session) =>
              // The context must be populated for the flow to run as it does in a request: the
              // repository and every DbEventHandler read the session from the ambient context.
              // The staleness guard is in the handler — `findAuthorizedUser` returns None for a
              // re-authorized user and the command becomes a silent no-op.
              ctx.locally(session):
                Clock.instant.flatMap(now => flow.execute(ExpireAccessCommand(now)))
        // Errors are swallowed and kept out of the return type, so no failure here can take down
        // the worker fiber.
        effect
          .either
          .flatMap:
            case Right(_) =>
              ZIO.unit
            case Left(e) =>
              ZIO.logError(s"Failed to expire session ${sessionId.asString}: ${e.getMessage}")
end JobRunner
