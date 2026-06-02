package whitelabel.captal.api

import whitelabel.captal.core.application.{Event, EventHandler}
import whitelabel.captal.core.user.Event as UserEvent
import whitelabel.captal.infra.session.{SessionContext, SessionService}
import zio.*

/** Test-only replacement for [[whitelabel.captal.infra.eventhandlers.UnifiAuthorizationHandler]].
  *
  * Why a dummy and not a mock UCG HTTP server? Tests exercising the post-Finish flow only care
  * about the wiring `POST /finish → UserFinishedProcess event → handler → setAuthorized →
  * /status reads Authorized`. The actual UCG calls (URL encoding, JSON parsing, headers, TLS)
  * are a separate concern and belong in a focused unit test for `UnifiAuthorizationHandler`.
  *
  * Behaviour is gated by a `Ref[Boolean]` exposed as a service — by default it's `false`, which
  * mimics the "no UniFi config / handler skips" path. Tests that want authorization call
  * [[enable]] before running their flow.
  */
object TestUnifiAutoAuthorizeHandler:
  type Active = Ref[Boolean]

  val activeLayer: ULayer[Active] = ZLayer.fromZIO(Ref.make(false))

  val enable: ZIO[Active, Nothing, Unit] =
    ZIO.serviceWithZIO[Active](_.set(true))

  val disable: ZIO[Active, Nothing, Unit] =
    ZIO.serviceWithZIO[Active](_.set(false))

  /** Default 60 minutes — arbitrary but matches typical operator configuration. Tests don't
    * assert on the exact value, just that `accessExpiresAt` is non-null and in the future.
    */
  def apply(
      active: Active,
      ctx: SessionContext,
      sessionService: SessionService,
      durationMinutes: Int = 60): EventHandler[Task, Event] =
    new EventHandler[Task, Event]:
      def handle(events: List[Event]): Task[Unit] =
        events.collectFirst {
          case Event.User(UserEvent.UserFinishedProcess(_, _, _, occurredAt)) =>
            occurredAt
        } match
          case None =>
            ZIO.unit
          case Some(occurredAt) =>
            active.get.flatMap:
              case false =>
                ZIO.unit
              case true =>
                ctx.getOrFail.flatMap: session =>
                  sessionService.setAuthorized(
                    session.sessionId,
                    occurredAt.plusSeconds(durationMinutes.toLong * 60L))
end TestUnifiAutoAuthorizeHandler
