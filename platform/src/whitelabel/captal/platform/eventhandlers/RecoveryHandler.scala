package whitelabel.captal.platform.eventhandlers

import io.getquill.*
import whitelabel.captal.core.application.Event
import whitelabel.captal.core.recovery.{Event as RecoveryEvent, Verdict}
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.core.given
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.{ErrorRow, Errors, VideoFiles}
import zio.*

/** A client error as the page reported it, and where each way out stands. Server errors are
  * recorded where the API catches them, outside any event.
  */
object RecoveryHandler:
  def apply(): DbEventHandler[Event] =
    new DbEventHandler[Event]:
      def handle(events: List[Event], quill: QuillSqlite): Task[Unit] =
        import quill.*
        ZIO.foreachDiscard(events):
          case Event.Recovery(
                RecoveryEvent.ErrorRecorded(
                  errorId,
                  sessionId,
                  deviceId,
                  report,
                  fingerprint,
                  verdict,
                  occurredAt)) =>
            val row = ErrorRow(
              id = errorId.asString,
              sessionId = Some(sessionId),
              deviceId = Some(deviceId),
              origin = "client",
              message = report.message,
              detail = Errors.detail(report),
              fingerprint = fingerprint.map(_.value),
              verdict = Some(Errors.verdict(verdict)),
              createdAt = VideoFiles.stamp(occurredAt),
              availableAt = None,
              redeemedAt = None
            )
            run(query[ErrorRow].insertValue(lift(row))).unit
          case Event.Recovery(RecoveryEvent.RecoveryOffered(errorId, _, availableAt, _)) =>
            run(
              query[ErrorRow]
                .filter(_.id == lift(errorId.asString))
                .update(_.availableAt -> lift(Option(VideoFiles.stamp(availableAt))))).unit
          case Event.Recovery(RecoveryEvent.RecoveryShortened(errorId, _, availableAt, _)) =>
            run(
              query[ErrorRow]
                .filter(_.id == lift(errorId.asString))
                .update(
                  _.availableAt -> lift(Option(VideoFiles.stamp(availableAt))),
                  _.verdict     -> lift(Option(Errors.verdict(Verdict.Plausible(true)))))).unit
          case Event.Recovery(RecoveryEvent.RecoveryRedeemed(errorId, _, occurredAt)) =>
            run(
              query[ErrorRow]
                .filter(_.id == lift(errorId.asString))
                .update(_.redeemedAt -> lift(Option(VideoFiles.stamp(occurredAt))))).unit
          case _ =>
            ZIO.unit
      end handle
end RecoveryHandler
