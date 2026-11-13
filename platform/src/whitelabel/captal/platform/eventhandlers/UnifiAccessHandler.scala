package whitelabel.captal.platform.eventhandlers

import whitelabel.captal.core.access.Event as AccessEvent
import whitelabel.captal.core.application.{Event, EventHandler}
import whitelabel.captal.core.device.Mac
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
  *   - [[whitelabel.captal.core.access.Event.AccessGranted]] → `AUTHORIZE_GUEST_ACCESS`, and on
  *     success marks the session `Phase.Authorized` with `expiresAt = occurredAt +
  *     defaultDurationMinutes`.
  *   - [[whitelabel.captal.core.access.Event.AccessExpired]] → `UNAUTHORIZE_GUEST_ACCESS`, which
  *     also disconnects the client. No session write here: the transactional
  *     [[SessionExpirationHandler]] already reset the row before this runs.
  *
  * The HTTP mechanics live in [[whitelabel.captal.platform.UnifiClient]]; this is only the event
  * adapter. It must stay outside the transactional chain (composed via `.andThen`, so it runs after
  * the commit). Both branches log and skip on failure.
  *
  * @param unifiFor
  *   The controller of the access point a session came through, with the location's access
  *   duration, read on every event: a venue's APs need not share a controller, and a change to one
  *   in the backoffice takes effect without a restart. None, and both branches are skipped.
  * @param unifiEnabled
  *   Whether the location has UniFi calls enabled, evaluated on every event so that turning it off
  *   in the backoffice takes effect without a restart. False skips both branches.
  */
object UnifiAccessHandler:

  def apply(
      unifiFor: Option[Mac] => Task[Option[UnifiAccess]],
      unifiEnabled: Task[Boolean],
      ctx: SessionContext,
      sessionService: SessionService,
      unifiClient: UnifiClient,
      jobQueue: JobQueue): EventHandler[Task, Event] =
    new EventHandler[Task, Event]:
      def handle(events: List[Event]): Task[Unit] =
        val finishedOpt = events.collectFirst {
          case Event.Access(AccessEvent.AccessGranted(_, _, occurredAt)) =>
            occurredAt
        }
        val expiredOpt = events.collectFirst {
          case Event.Access(AccessEvent.AccessExpired(_, _, occurredAt)) =>
            occurredAt
        }
        (finishedOpt, expiredOpt) match
          case (Some(occurredAt), _) =>
            withConfig("authorize")(authorize(_, occurredAt))
          case (_, Some(_)) =>
            withConfig("revoke")(revoke)
          case _ =>
            ZIO.unit

      /** Shared guard: with UniFi disabled for the location, or with no configuration for the AP
        * the session came through (dev, tests, an AP removed since the guest arrived), it logs and
        * moves on.
        */
      private def withConfig(action: String)(body: UnifiAccess => Task[Unit]): Task[Unit] =
        unifiEnabled
          .catchAll: error =>
            ZIO.logError(
              s"UniFi $action: could not read whether it is enabled — skipping: ${error
                  .getMessage}") *> ZIO.succeed(false)
          .flatMap:
            case false =>
              ZIO.logInfo(s"UniFi $action: disabled for current location — skipping")
            case true =>
              ctx
                .getOrFail
                .flatMap: session =>
                  val ap = session.apMac.fold("none")(_.show)
                  unifiFor(session.apMac)
                    .catchAll: error =>
                      ZIO.logError(
                        s"UniFi $action: could not read the configuration of AP $ap — skipping: ${error
                            .getMessage}") *> ZIO.none
                    .flatMap:
                      case None =>
                        ZIO.logWarning(
                          s"UniFi $action: AP $ap has no configuration for the current location — skipping (session=${session
                              .sessionId
                              .asString})")
                      case Some(unifiCfg) =>
                        body(unifiCfg)

      private def authorize(unifiCfg: UnifiAccess, occurredAt: java.time.Instant): Task[Unit] =
        for
          session <- ctx.getOrFail
          expiresAt = occurredAt.plusSeconds(unifiCfg.defaultDurationMinutes * 60L)
          result <-
            ZIO
              .fromOption(session.clientMac)
              .orElseFail(new IllegalStateException("the session has no client MAC"))
              .flatMap(unifiClient.authorizeGuest(unifiCfg, _))
              .either
          _ <-
            result match
              case Left(error) =>
                ZIO.logError(
                  s"UniFi authorize failed for session ${session.sessionId.asString}: ${error
                      .getMessage}")
              case Right(_) =>
                // The only place an authorization is born, and so the scheduler's only producer.
                // What gets enqueued is data, not an effect, so startup rehydration can rebuild
                // the same job by reading `sessions`.
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
            session.clientMac match
              case None =>
                ZIO.logWarning(
                  s"UniFi revoke: no clientMac — skipping (session=${session.sessionId.asString})")
              case Some(mac) =>
                unifiClient
                  .revokeGuestAccess(unifiCfg, mac)
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
