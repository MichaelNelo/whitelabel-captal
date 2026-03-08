package whitelabel.captal.infra.session

import io.getquill.*
import whitelabel.captal.core.application.Phase
import whitelabel.captal.core.infrastructure.SessionData
import whitelabel.captal.core.survey.question.FullyQualifiedQuestionId
import whitelabel.captal.core.{survey, user, video}
import whitelabel.captal.infra.schema.QuillSqlite
import whitelabel.captal.infra.schema.core.given
import whitelabel.captal.infra.schema.given
import whitelabel.captal.infra.{DeviceRow, SessionRow}
import zio.*

trait SessionService:
  def findById(sessionId: user.SessionId): Task[Option[SessionData]]
  def create(userAgent: String, locale: String, phase: Phase): Task[SessionData]
  def setPhase(sessionId: user.SessionId, phase: Phase): Task[Unit]
  def setLocale(sessionId: user.SessionId, locale: String): Task[Unit]
  def setCurrentQuestion(sessionId: user.SessionId, question: FullyQualifiedQuestionId): Task[Unit]
  def clearCurrentQuestion(sessionId: user.SessionId): Task[Unit]

object SessionService:
  inline def findByIdQuery = quote: (sessionIdParam: user.SessionId) =>
    query[SessionRow].filter(_.id == sessionIdParam)

  inline def updateCurrentSurveyQuery = quote:
    (
        sessionIdParam: user.SessionId,
        surveyIdParam: survey.Id,
        questionIdParam: survey.question.Id) =>
      query[SessionRow]
        .filter(_.id == sessionIdParam)
        .update(
          _.currentSurveyId   -> Some(surveyIdParam),
          _.currentQuestionId -> Some(questionIdParam))

  inline def clearCurrentSurveyQuery = quote: (sessionIdParam: user.SessionId) =>
    query[SessionRow]
      .filter(_.id == sessionIdParam)
      .update(_.currentSurveyId -> None, _.currentQuestionId -> None)

  inline def updatePhaseQuery = quote: (sessionIdParam: user.SessionId, phaseParam: Phase) =>
    query[SessionRow].filter(_.id == sessionIdParam).update(_.phase -> phaseParam)

  inline def updateLocaleQuery = quote: (sessionIdParam: user.SessionId, localeParam: String) =>
    query[SessionRow].filter(_.id == sessionIdParam).update(_.locale -> localeParam)

  inline def updateCurrentVideoQuery = quote:
    (sessionIdParam: user.SessionId, videoIdParam: video.Id) =>
      query[SessionRow]
        .filter(_.id == sessionIdParam)
        .update(_.currentVideoId -> Some(videoIdParam))

  inline def clearCurrentVideoQuery = quote: (sessionIdParam: user.SessionId) =>
    query[SessionRow]
      .filter(_.id == sessionIdParam)
      .update(_.currentVideoId -> None)

  inline def updateLastPromoVideoQuery = quote:
    (sessionIdParam: user.SessionId, videoIdParam: video.Id) =>
      query[SessionRow]
        .filter(_.id == sessionIdParam)
        .update(_.lastPromoVideoId -> Some(videoIdParam))

  def apply(quill: QuillSqlite): SessionService =
    new SessionService:
      import quill.*

      def findById(sessionId: user.SessionId): Task[Option[SessionData]] =
        run(findByIdQuery(lift(sessionId))).map(_.headOption.map(toSessionData)).orDie

      def create(userAgent: String, locale: String, phase: Phase): Task[SessionData] =
        val sessionId = user.SessionId.generate
        val deviceId = user.DeviceId.fromUserAgent(userAgent)
        val now = java.time.Instant.now.toString

        // Upsert device record
        val deviceRow = DeviceRow(
          id = deviceId,
          userAgent = userAgent,
          createdAt = now,
          updatedAt = now)

        val upsertDevice = run(
          query[DeviceRow]
            .insertValue(lift(deviceRow))
            .onConflictUpdate(_.id)(
              (t, e) => t.userAgent -> e.userAgent,
              (t, _) => t.updatedAt -> lift(now)))

        // Create session
        val sessionRow = SessionRow(
          id = sessionId,
          userId = None,
          deviceId = deviceId,
          locale = locale,
          phase = phase,
          currentSurveyId = None,
          currentQuestionId = None,
          currentVideoId = None,
          lastPromoVideoId = None,
          createdAt = now)

        val insertSession = run(
          query[SessionRow].insert(
            _.id               -> lift(sessionRow.id),
            _.userId           -> lift(sessionRow.userId),
            _.deviceId         -> lift(sessionRow.deviceId),
            _.locale           -> lift(sessionRow.locale),
            _.phase            -> lift(sessionRow.phase),
            _.currentSurveyId  -> lift(sessionRow.currentSurveyId),
            _.currentQuestionId -> lift(sessionRow.currentQuestionId),
            _.currentVideoId   -> lift(sessionRow.currentVideoId),
            _.lastPromoVideoId -> lift(sessionRow.lastPromoVideoId),
            _.createdAt        -> lift(sessionRow.createdAt)))

        (upsertDevice *> insertSession).orDie *>
          ZIO.succeed(SessionData(sessionId, None, locale, phase, None, None, None))
      end create

      def setCurrentQuestion(
          sessionId: user.SessionId,
          question: FullyQualifiedQuestionId): Task[Unit] =
        run(
          updateCurrentSurveyQuery(
            lift(sessionId),
            lift(question.surveyId),
            lift(question.questionId))).unit.orDie

      def clearCurrentQuestion(sessionId: user.SessionId): Task[Unit] =
        run(clearCurrentSurveyQuery(lift(sessionId))).unit.orDie

      def setPhase(sessionId: user.SessionId, phase: Phase): Task[Unit] =
        run(updatePhaseQuery(lift(sessionId), lift(phase))).unit.orDie

      def setLocale(sessionId: user.SessionId, locale: String): Task[Unit] =
        run(updateLocaleQuery(lift(sessionId), lift(locale))).unit.orDie

  private def toSessionData(row: SessionRow): SessionData =
    val currentQuestion =
      (row.currentSurveyId, row.currentQuestionId) match
        case (Some(surveyId), Some(questionId)) =>
          Some(FullyQualifiedQuestionId(surveyId, questionId))
        case _ =>
          None
    SessionData(
      row.id,
      row.userId,
      row.locale,
      row.phase,
      currentQuestion,
      row.currentVideoId,
      row.lastPromoVideoId)

  val layer: ZLayer[QuillSqlite, Nothing, SessionService] = ZLayer.fromFunction(apply)
end SessionService
