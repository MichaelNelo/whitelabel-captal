package whitelabel.captal.infra

import io.getquill.*
import whitelabel.captal.core.application.Phase
import whitelabel.captal.core.infrastructure.SessionData
import whitelabel.captal.core.{survey, user}
import whitelabel.captal.infra.QuillSchema.given
import zio.*

trait SessionService:
  def findById(sessionId: user.SessionId): Task[Option[SessionData]]
  def create(deviceId: user.DeviceId, locale: String, phase: Phase): Task[SessionData]
  def setPhase(sessionId: user.SessionId, phase: Phase): Task[Unit]
  def setCurrentSurvey(
      sessionId: user.SessionId,
      surveyId: survey.Id,
      questionId: survey.question.Id): Task[Unit]
  def clearCurrentSurvey(sessionId: user.SessionId): Task[Unit]

object SessionService:
  inline def findByIdQuery = quote: (sessionId: String) =>
    query[SessionRow].filter(_.id == sessionId)

  inline def updateCurrentSurveyQuery = quote:
    (sessionId: String, surveyId: String, questionId: String) =>
      query[SessionRow]
        .filter(_.id == sessionId)
        .update(_.currentSurveyId -> Some(surveyId), _.currentQuestionId -> Some(questionId))

  inline def clearCurrentSurveyQuery = quote: (sessionId: String) =>
    query[SessionRow]
      .filter(_.id == sessionId)
      .update(_.currentSurveyId -> None, _.currentQuestionId -> None)

  inline def updatePhaseQuery = quote: (sessionId: String, phase: String) =>
    query[SessionRow].filter(_.id == sessionId).update(_.phase -> phase)

  def apply(quill: QuillSqlite): SessionService =
    new SessionService:
      import quill.*

      def findById(sessionId: user.SessionId): Task[Option[SessionData]] =
        run(findByIdQuery(lift(sessionId.asString))).map(_.headOption.flatMap(toSessionData)).orDie

      def create(deviceId: user.DeviceId, locale: String, phase: Phase): Task[SessionData] =
        val sessionId = user.SessionId.generate
        val now = java.time.Instant.now.toString
        val row = SessionRow(
          id = sessionId.asString,
          userId = None,
          deviceId = deviceId.value,
          locale = locale,
          phase = phaseToString(phase),
          currentSurveyId = None,
          currentQuestionId = None,
          createdAt = now
        )
        run(
          query[SessionRow].insert(
            _.id                -> lift(row.id),
            _.userId            -> lift(row.userId),
            _.deviceId          -> lift(row.deviceId),
            _.locale            -> lift(row.locale),
            _.phase             -> lift(row.phase),
            _.currentSurveyId   -> lift(row.currentSurveyId),
            _.currentQuestionId -> lift(row.currentQuestionId),
            _.createdAt         -> lift(row.createdAt)
          )).orDie *> ZIO.succeed(SessionData(sessionId, None, locale, phase, None, None))
      end create

      def setCurrentSurvey(
          sessionId: user.SessionId,
          surveyId: survey.Id,
          questionId: survey.question.Id): Task[Unit] =
        run(
          updateCurrentSurveyQuery(
            lift(sessionId.asString),
            lift(surveyId.asString),
            lift(questionId.asString))).unit.orDie

      def clearCurrentSurvey(sessionId: user.SessionId): Task[Unit] =
        run(clearCurrentSurveyQuery(lift(sessionId.asString))).unit.orDie

      def setPhase(sessionId: user.SessionId, phase: Phase): Task[Unit] =
        run(updatePhaseQuery(lift(sessionId.asString), lift(phaseToString(phase)))).unit.orDie

  private def toSessionData(row: SessionRow): Option[SessionData] =
    for
      sessionId <- user.SessionId.fromString(row.id)
      phase     <- parsePhase(row.phase)
    yield SessionData(
      sessionId,
      row.userId.flatMap(user.Id.fromString),
      row.locale,
      phase,
      row.currentSurveyId.flatMap(survey.Id.fromString),
      row.currentQuestionId.flatMap(survey.question.Id.fromString)
    )

  private def parsePhase(s: String): Option[Phase] =
    s match
      case "identification_question" =>
        Some(Phase.IdentificationQuestion)
      case "advertiser_video" =>
        Some(Phase.AdvertiserVideo)
      case "advertiser_question" =>
        Some(Phase.AdvertiserQuestion)
      case "ready" =>
        Some(Phase.Ready)
      case _ =>
        None

  def phaseToString(phase: Phase): String =
    phase match
      case Phase.IdentificationQuestion =>
        "identification_question"
      case Phase.AdvertiserVideo =>
        "advertiser_video"
      case Phase.AdvertiserQuestion =>
        "advertiser_question"
      case Phase.Ready =>
        "ready"

  val layer: ZLayer[QuillSqlite, Nothing, SessionService] = ZLayer.fromFunction(apply)
end SessionService
