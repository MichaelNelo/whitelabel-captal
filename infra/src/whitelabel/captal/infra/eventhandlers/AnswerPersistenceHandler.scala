package whitelabel.captal.infra.eventhandlers

import java.time.Instant
import java.util.UUID

import io.circe.syntax.*
import io.getquill.*
import io.getquill.context.sql.idiom.SqlIdiom
import io.getquill.jdbczio.Quill
import whitelabel.captal.core.application.Event
import whitelabel.captal.core.survey.Event as SurveyEvent
import whitelabel.captal.core.survey.question.Event as QuestionEvent
import whitelabel.captal.core.survey.question.codecs.given
import whitelabel.captal.infra.{AnswerRow, DbEventHandler}
import zio.*

object AnswerPersistenceHandler:
  def apply[D <: SqlIdiom, N <: NamingStrategy]: DbEventHandler[D, N, Event] =
    new DbEventHandler[D, N, Event]:
      def handle(events: List[Event], quill: Quill[D, N]): Task[Unit] =
        import quill.*
        val answers = events.flatMap(toAnswerRow)
        if answers.nonEmpty then
          run(liftQuery(answers).foreach(a => query[AnswerRow].insertValue(a))).unit.orDie
        else
          ZIO.unit

  private def toAnswerRow(event: Event): Option[AnswerRow] =
    event match
      case Event.Survey(SurveyEvent.QuestionAnswered(_, questionEvent)) =>
        Some(questionEventToRow(questionEvent))
      case Event.User(_) =>
        None

  private def questionEventToRow(event: QuestionEvent): AnswerRow =
    val now = Instant.now.toString
    event match
      case QuestionEvent.EmailQuestionAnswered(
            userId,
            sessionId,
            _,
            questionId,
            answer,
            _,
            occurredAt) =>
        AnswerRow(
          id = UUID.randomUUID.toString,
          userId = userId.asString,
          sessionId = sessionId.asString,
          questionId = questionId.asString,
          answerValue = answer.value.asJson.noSpaces,
          answeredAt = occurredAt.toString,
          createdAt = now)
      case QuestionEvent.ProfilingQuestionAnswered(
            userId,
            sessionId,
            _,
            questionId,
            answer,
            _,
            occurredAt) =>
        AnswerRow(
          id = UUID.randomUUID.toString,
          userId = userId.asString,
          sessionId = sessionId.asString,
          questionId = questionId.asString,
          answerValue = answer.value.asJson.noSpaces,
          answeredAt = occurredAt.toString,
          createdAt = now)
      case QuestionEvent.LocationQuestionAnswered(
            userId,
            sessionId,
            _,
            questionId,
            _,
            answer,
            _,
            occurredAt) =>
        AnswerRow(
          id = UUID.randomUUID.toString,
          userId = userId.asString,
          sessionId = sessionId.asString,
          questionId = questionId.asString,
          answerValue = answer.value.asJson.noSpaces,
          answeredAt = occurredAt.toString,
          createdAt = now)
      case QuestionEvent.AdvertiserQuestionAnswered(
            userId,
            sessionId,
            _,
            _,
            questionId,
            answer,
            _,
            occurredAt) =>
        AnswerRow(
          id = UUID.randomUUID.toString,
          userId = userId.asString,
          sessionId = sessionId.asString,
          questionId = questionId.asString,
          answerValue = answer.value.asJson.noSpaces,
          answeredAt = occurredAt.toString,
          createdAt = now)
