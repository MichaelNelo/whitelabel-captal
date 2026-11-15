package whitelabel.captal.platform.repositories

import java.time.Instant

import io.getquill.*
import whitelabel.captal.core.device.{Device, Matching, Policy, Signals, State}
import whitelabel.captal.core.infrastructure.{
  DeviceLookup,
  SessionData,
  SessionRepository,
  UserDirectory
}
import whitelabel.captal.core.user
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.core.given
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.session.SessionService
import whitelabel.captal.platform.{DeviceRow, Terms, UserRow}
import zio.*

/** What entering the portal reads, outside any session: the session a cookie names, the device a
  * redirect's signals match under the deployment's `policy`, and whether a user is still known.
  * Each device comes with where it stands with the terms of service in force.
  */
object EntryRepositoriesQuill:
  type Repositories = SessionRepository[Task] & DeviceLookup[Task] & UserDirectory[Task]

  def apply(quill: QuillSqlite, sessions: SessionService, policy: Policy): Repositories =
    import quill.*

    /** The device in `row`, accepted when it accepted the version in force. */
    def withTerms(row: DeviceRow): Task[Device[State]] = Terms
      .current(quill)
      .map: current =>
        val accepted =
          for
            terms <- current
            id    <- row.termsId.filter(_ == terms.id)
            at    <- row.termsAcceptedAt
          yield State.Accepted(id, Instant.parse(at))
        Device(row.id, accepted.getOrElse(State.NotAccepted))

    inline def deviceMatching(inline m: Matching, seen: Signals): Task[Option[DeviceRow]] = run(
      query[DeviceRow]
        .filter(d =>
          m.matches(
            Signals(d.clientMac, d.fingerprint),
            Signals(lift(seen.clientMac), lift(seen.fingerprint))))
        .take(1)).map(_.headOption)

    new SessionRepository[Task] with DeviceLookup[Task] with UserDirectory[Task]:
      def find(id: user.SessionId): Task[Option[SessionData]] = sessions.findById(id)

      def matching(seen: Signals): Task[Option[Device[State]]] =
        val found =
          if !policy.covers(seen) then
            ZIO.none
          else
            policy match
              case Policy.ByFingerprint =>
                deviceMatching(Matching.ByFingerprint, seen)
              case Policy.ByMac =>
                deviceMatching(Matching.ByMac, seen)
              case Policy.ByMacAndFingerprint =>
                deviceMatching(Matching.ByMacAndFingerprint, seen)
        found.flatMap(ZIO.foreach(_)(withTerms)).orDie

      def byId(id: user.DeviceId): Task[Option[Device[State]]] =
        run(query[DeviceRow].filter(_.id == lift(id)))
          .map(_.headOption)
          .flatMap(ZIO.foreach(_)(withTerms))
          .orDie

      def exists(id: user.Id): Task[Boolean] =
        run(query[UserRow].filter(_.id == lift(id)).take(1)).map(_.nonEmpty).orDie
    end new
  end apply
end EntryRepositoriesQuill
