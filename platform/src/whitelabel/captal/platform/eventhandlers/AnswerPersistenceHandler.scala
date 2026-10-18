package whitelabel.captal.platform.eventhandlers

import java.util.UUID

import io.getquill.*
import whitelabel.captal.core.application.Event
import whitelabel.captal.core.survey.Event as SurveyEvent
import whitelabel.captal.core.survey.question.Event as QuestionEvent
import whitelabel.captal.core.{survey, user}
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.core.given
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.session.SessionContext
import whitelabel.captal.platform.surveySchemas.given
import whitelabel.captal.platform.{AnswerRow, surveySchemas}
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
          instant     <- Clock.instant
          answers = events.flatMap(toAnswerRow(_, sessionData.sessionId, instant.toString))
          _ <-
            if answers.nonEmpty then
              run(insertAnswersQuery(liftQuery(answers))).unit.orDie
            else
              ZIO.unit
        yield ()

  private def toAnswerRow(event: Event, sessionId: user.SessionId, now: String): Option[AnswerRow] =
    event match
      case Event.Survey(SurveyEvent.QuestionAnswered(_, questionEvent)) =>
        Some(questionEventToRow(questionEvent, sessionId, now))
      case Event.User(_) =>
        None
      case Event.Video(_) =>
        None

  private def questionEventToRow(
      event: QuestionEvent,
      sessionId: user.SessionId,
      now: String): AnswerRow =
    event match
      case QuestionEvent.EmailQuestionAnswered(userId, _, questionId, answer, occurredAt) =>
        AnswerRow(
          id = UUID.randomUUID.toString,
          userId = userId,
          sessionId = sessionId,
          questionId = questionId,
          answerValue = surveySchemas.encode(answer.value),
          answeredAt = occurredAt.toString,
          createdAt = now
        )
      case QuestionEvent.ProfilingQuestionAnswered(userId, _, questionId, answer, occurredAt) =>
        AnswerRow(
          id = UUID.randomUUID.toString,
          userId = userId,
          sessionId = sessionId,
          questionId = questionId,
          answerValue = surveySchemas.encode(answer.value),
          answeredAt = occurredAt.toString,
          createdAt = now
        )
      case QuestionEvent.LocationQuestionAnswered(userId, _, questionId, _, answer, occurredAt) =>
        AnswerRow(
          id = UUID.randomUUID.toString,
          userId = userId,
          sessionId = sessionId,
          questionId = questionId,
          answerValue = surveySchemas.encode(answer.value),
          answeredAt = occurredAt.toString,
          createdAt = now
        )
      case QuestionEvent.AdvertiserQuestionAnswered(userId, _, _, questionId, answer, occurredAt) =>
        AnswerRow(
          id = UUID.randomUUID.toString,
          userId = userId,
          sessionId = sessionId,
          questionId = questionId,
          answerValue = surveySchemas.encode(answer.value),
          answeredAt = occurredAt.toString,
          createdAt = now
        )
    end match
  end questionEventToRow
end AnswerPersistenceHandler
