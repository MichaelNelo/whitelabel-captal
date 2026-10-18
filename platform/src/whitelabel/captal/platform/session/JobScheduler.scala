package whitelabel.captal.platform.session

import java.time.Instant

import scala.collection.immutable.SortedMap

import whitelabel.captal.core.application.Flow
import whitelabel.captal.core.application.commands.ExpireAccessCommand
import whitelabel.captal.core.user
import zio.*

/** A job with a deadline. Only one exists today; the enum is here so future scheduled work (purging
  * dead sessions, refreshing tokens, invalidating the CDN) can reuse the same fiber without a
  * refactor.
  */
enum ScheduledJob:
  case ExpireAccess(sessionId: user.SessionId)

final case class ScheduledAt(deadline: Instant, job: ScheduledJob)

/** Producer side of the scheduler: just the inbound queue.
  *
  * Kept separate from [[JobRunner]] to break a layer cycle. `UnifiAccessHandler` lives inside
  * `eventHandlerLayer` and needs to schedule jobs; the runner needs to execute a `Flow`, which in
  * turn depends on `eventHandlerLayer`. As a single service the graph would be cyclic:
  *
  * {{{
  * eventHandlerLayer → Scheduler → Flow[ExpireAccess] → eventHandlerLayer   ✗
  * }}}
  *
  * Having no dependencies, `JobQueue` can be injected into `eventHandlerLayer` and the graph stays
  * acyclic.
  */
final class JobQueue private (private[session] val inbox: Queue[ScheduledAt]):
  def schedule(at: ScheduledAt): UIO[Unit] = inbox.offer(at).unit

object JobQueue:
  /** Unbounded: at most one entry per authorized session of this location, so growth is bounded by
    * the venue's capacity.
    */
  val layer: ULayer[JobQueue] = ZLayer.fromZIO(Queue.unbounded[ScheduledAt].map(new JobQueue(_)))

/** Consumer side of the scheduler. */
trait JobRunner:
  /** Forks the scheduler fiber and the worker fiber, and rehydrates pending expirations.
    *
    * Explicit on purpose, rather than forking as a side effect of building the layer: a service
    * nobody references gets pruned from the graph by ZLayer (with an easy-to-miss `ZLAYER WARNING`)
    * and the fibers would never start — with the tests still green. Calling it from `Main.run`
    * makes it visible where background work begins.
    *
    * Takes `Scope` so the fibers die with the application, and so a test can start and clean up a
    * runner inside a `ZIO.scoped` without leaking them across cases.
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

  /** Direct constructor. Exists so a test can build its own runner per case and start it inside its
    * own `ZIO.scoped`, rather than sharing the one from the environment — two `start` calls on the
    * same runner would fork schedulers competing over the same queue.
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
          // Rehydration: the scheduler's state lives in memory and does not survive a task
          // redeploy. Deadlines already past fire right away (the computed wait is 0).
          pending <- sessions.findPendingExpirations().orDie
          _       <-
            ZIO.foreachDiscard(pending): (sessionId, deadline) =>
              jobQueue.schedule(ScheduledAt(deadline, ScheduledJob.ExpireAccess(sessionId)))
          _ <- ZIO.logInfo(s"JobRunner started, rehydrated ${pending.size} pending expiration(s)")
        yield ()

  /** A single fiber. `inbox.take.timeout(wait)` covers both signals at once: if a job arrives the
    * `take` returns and the queue is reordered; if the timeout elapses, some deadlines came due. A
    * job with an earlier deadline recomputes the wait by itself, without interrupting or recreating
    * any fiber.
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
              // O(n), but n = authorized sessions of one location. Irrelevant.
              (due_, rest) = pending.partition((at, _) => !at.isAfter(ahora))
              _ <- ZIO.foreachDiscard(due_.values.flatten)(due.offer)
            yield rest
      _ <- schedulerLoop(inbox, due, next)
    yield ()

  /** A single fiber → concurrency of 1 against the UCG. Deliberate: the
    * `Fargate → Tailscale → subnet router → LAN` link is slow and fragile. `UnifiClient`'s
    * `.timeout` bounds how long one job can hold up the rest of the queue.
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
              // Populating the context is what makes the flow run exactly as in a request: the
              // repository and every DbEventHandler read the session from the ambient context.
              //
              // The staleness guard lives in the handler, not here: if the job went stale because
              // the user got re-authorized, `findAuthorizedUser` returns None and the command is a
              // silent no-op.
              ctx.locally(session):
                Clock.instant.flatMap(now => flow.execute(ExpireAccessCommand(now)))
        // The error is swallowed here and absent from the return type: neither an unreachable UCG
        // nor an rqlite failure can take down the worker fiber.
        effect
          .either
          .flatMap:
            case Right(_) =>
              ZIO.unit
            case Left(e) =>
              ZIO.logError(s"Failed to expire session ${sessionId.asString}: ${e.getMessage}")
end JobRunner
