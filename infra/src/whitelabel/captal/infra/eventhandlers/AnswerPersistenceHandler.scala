package whitelabel.captal.infra.eventhandlers

import java.time.Instant
import java.util.UUID

import io.circe.syntax.*
import io.getquill.*
import whitelabel.captal.core.application.Event
import whitelabel.captal.core.survey.Event as SurveyEvent
import whitelabel.captal.core.survey.question.Event as QuestionEvent
import whitelabel.captal.core.survey.question.codecs.given
import whitelabel.captal.core.{survey, user}
import whitelabel.captal.infra.schema.given
import whitelabel.captal.infra.schema.core.given
import whitelabel.captal.infra.AnswerRow
import whitelabel.captal.infra.session.SessionContext
import whitelabel.captal.infra.schema.QuillSqlite
import zio.*

object AnswerPersistenceHandler:
  inline def insertAnswersQuery = quote: (answers: Query[AnswerRow]) =>
    answers.foreach(a =>
      query[AnswerRow].insert(
        _.id          -> a.id,
        _.userId      -> a.userId,
        _.sessionId   -> a.sessionId,
        _.questionId  -> a.questionId,
        _.answerValue -> a.answerValue,
        _.answeredAt  -> a.answeredAt,
        _.createdAt   -> a.createdAt
      ))

  def apply(ctx: SessionContext): DbEventHandler[Event] =
    new DbEventHandler[Event]:
      def handle(events: List[Event], quill: QuillSqlite): Task[Unit] =
        import quill.*
        for
          sessionData <- ctx.getOrFail
          answers = events.flatMap(toAnswerRow(_, sessionData.sessionId))
          _ <-
            if answers.nonEmpty then
              run(insertAnswersQuery(liftQuery(answers))).unit.orDie
            else
              ZIO.unit
        yield ()

  private def toAnswerRow(event: Event, sessionId: user.SessionId): Option[AnswerRow] =
    event match
      case Event.Survey(SurveyEvent.QuestionAnswered(_, questionEvent)) =>
        Some(questionEventToRow(questionEvent, sessionId))
      case Event.User(_) =>
        None

  private def questionEventToRow(event: QuestionEvent, sessionId: user.SessionId): AnswerRow =
    val now = Instant.now.toString
    event match
      case QuestionEvent.EmailQuestionAnswered(userId, _, questionId, answer, occurredAt) =>
        AnswerRow(
          id = UUID.randomUUID.toString,
          userId = userId,
          sessionId = sessionId,
          questionId = questionId,
          answerValue = answer.value.asJson.noSpaces,
          answeredAt = occurredAt.toString,
          createdAt = now
        )
      case QuestionEvent.ProfilingQuestionAnswered(userId, _, questionId, answer, occurredAt) =>
        AnswerRow(
          id = UUID.randomUUID.toString,
          userId = userId,
          sessionId = sessionId,
          questionId = questionId,
          answerValue = answer.value.asJson.noSpaces,
          answeredAt = occurredAt.toString,
          createdAt = now
        )
      case QuestionEvent.LocationQuestionAnswered(userId, _, questionId, _, answer, occurredAt) =>
        AnswerRow(
          id = UUID.randomUUID.toString,
          userId = userId,
          sessionId = sessionId,
          questionId = questionId,
          answerValue = answer.value.asJson.noSpaces,
          answeredAt = occurredAt.toString,
          createdAt = now
        )
      case QuestionEvent.AdvertiserQuestionAnswered(userId, _, _, questionId, answer, occurredAt) =>
        AnswerRow(
          id = UUID.randomUUID.toString,
          userId = userId,
          sessionId = sessionId,
          questionId = questionId,
          answerValue = answer.value.asJson.noSpaces,
          answeredAt = occurredAt.toString,
          createdAt = now
        )
    end match
  end questionEventToRow
end AnswerPersistenceHandler
