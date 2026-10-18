package whitelabel.captal.platform.eventhandlers

import whitelabel.captal.core.application.{Event, EventHandler}
import whitelabel.captal.core.user.Event as UserEvent
import whitelabel.captal.platform.session.{
  JobQueue,
  ScheduledAt,
  ScheduledJob,
  SessionContext,
  SessionService
}
import whitelabel.captal.platform.{UnifiAccess, UnifiClient}
import zio.*

/** Post-commit event handler that mirrors the guest-access lifecycle onto the UniFi controller.
  *
  * Handles both ends of the lifecycle symmetrically:
  *
  *   - [[whitelabel.captal.core.user.Event.UserFinishedProcess]] → `AUTHORIZE_GUEST_ACCESS`, and on
  *     success marks the session `Phase.Authorized` with `expiresAt = occurredAt +
  *     defaultDurationMinutes`.
  *   - [[whitelabel.captal.core.user.Event.AccessExpired]] → `UNAUTHORIZE_GUEST_ACCESS`, which per
  *     the Integration v1 spec also disconnects the client. No session write here: the
  *     transactional [[SessionExpirationHandler]] already reset the row before this runs.
  *
  * The HTTP mechanics live in [[whitelabel.captal.platform.UnifiClient]]; this is only the event
  * adapter. Lives outside the transactional chain (composed via `.andThen` after the transaction
  * commits) so HTTP failures don't roll back DB writes.
  *
  * Failure behaviour is the same on both branches — log and skip:
  *   - Authorize failing leaves the session in `Phase.Ready`, so `/api/finish` retries seamlessly.
  *   - Revoke failing is already absorbed: the reset is committed, so the session is back at
  *     `Welcome` regardless, and the UCG expires the voucher on its own `timeLimitMinutes`.
  */
object UnifiAccessHandler:

  def apply(
      unifi: Option[UnifiAccess],
      ctx: SessionContext,
      sessionService: SessionService,
      unifiClient: UnifiClient,
      jobQueue: JobQueue): EventHandler[Task, Event] =
    new EventHandler[Task, Event]:
      def handle(events: List[Event]): Task[Unit] =
        val finishedOpt = events.collectFirst {
          case Event.User(UserEvent.UserFinishedProcess(_, _, _, occurredAt)) =>
            occurredAt
        }
        val expiredOpt = events.collectFirst {
          case Event.User(UserEvent.AccessExpired(_, occurredAt)) =>
            occurredAt
        }
        (finishedOpt, expiredOpt) match
          case (Some(occurredAt), _) =>
            withConfig("authorize")(authorize(_, occurredAt))
          case (_, Some(_)) =>
            withConfig("revoke")(revoke)
          case _ =>
            ZIO.unit

      /** Shared guard: with no UniFi config (dev, tests, locations without a controller) it logs
        * and moves on, which has always been this handler's convention.
        */
      private def withConfig(action: String)(body: UnifiAccess => Task[Unit]): Task[Unit] =
        unifi match
          case None =>
            ZIO.logWarning(s"UniFi $action: no config for current location — skipping")
          case Some(unifiCfg) =>
            body(unifiCfg)

      private def authorize(unifiCfg: UnifiAccess, occurredAt: java.time.Instant): Task[Unit] =
        for
          session <- ctx.getOrFail
          expiresAt = occurredAt.plusSeconds(unifiCfg.defaultDurationMinutes * 60L)
          result <- unifiClient.authorizeGuest(unifiCfg, session.clientMac).either
          _      <-
            result match
              case Left(error) =>
                ZIO.logError(
                  s"UniFi authorize failed for session ${session.sessionId.asString}: ${error
                      .getMessage}")
              case Right(_) =>
                // The only place an authorization is born, hence the scheduler's only producer.
                // What gets enqueued is data, not an effect: that is what lets startup rehydration
                // rebuild the very same job by reading `sessions`.
                sessionService.setAuthorized(session.sessionId, expiresAt) *>
                  jobQueue.schedule(
                    ScheduledAt(expiresAt, ScheduledJob.ExpireAccess(session.sessionId))) *>
                  ZIO.logInfo(
                    s"UniFi authorized session ${session.sessionId.asString} until $expiresAt")
        yield ()

      private def revoke(unifiCfg: UnifiAccess): Task[Unit] =
        for
          session <- ctx.getOrFail
          _       <-
            if session.clientMac.trim.isEmpty then
              ZIO.logWarning(
                s"UniFi revoke: empty clientMac — skipping (session=${session.sessionId.asString})")
            else
              unifiClient
                .revokeGuestAccess(unifiCfg, session.clientMac)
                .either
                .flatMap:
                  case Left(error) =>
                    ZIO.logError(
                      s"UniFi revoke failed for session ${session.sessionId.asString}: ${error
                          .getMessage}")
                  case Right(_) =>
                    ZIO.logInfo(
                      s"UniFi guest access revoked for session ${session.sessionId.asString}")
        yield ()
      end revoke
end UnifiAccessHandler
