package whitelabel.captal.platform.session

import io.getquill.*
import whitelabel.captal.core.application.Phase
import whitelabel.captal.core.device.{Mac, Matching, Policy, Signals}
import whitelabel.captal.core.infrastructure.SessionData
import whitelabel.captal.core.survey.AdvertiserId
import whitelabel.captal.core.survey.question.FullyQualifiedQuestionId
import whitelabel.captal.core.{survey, user, video}
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.core.given
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.{DeviceRow, SessionRow}
import zio.*

/** Sessions as the routes past the welcome read and move them. A session is started by entering the
  * portal ([[whitelabel.captal.core.application.commands.EnterPortalCommand]]), whose events write
  * it.
  */
trait SessionService:
  def findById(sessionId: user.SessionId): Task[Option[SessionData]]

  /** Which of a device's signals make two sessions the same device. */
  def matching: Policy

  def setPhase(sessionId: user.SessionId, phase: Phase): Task[Unit]
  def setLocale(sessionId: user.SessionId, locale: String): Task[Unit]
  def setCurrentQuestion(sessionId: user.SessionId, question: FullyQualifiedQuestionId): Task[Unit]
  def clearCurrentQuestion(sessionId: user.SessionId): Task[Unit]

  /** Mark the session as authorized (Phase.Authorized) until the given instant. */
  def setAuthorized(sessionId: user.SessionId, expiresAt: java.time.Instant): Task[Unit]

  /** Sessions of this location that are `Authorized` with an `accessExpiresAt` set. Used at
    * startup, to re-seed the expiration scheduler.
    */
  def findPendingExpirations(): Task[List[(user.SessionId, java.time.Instant)]]
end SessionService

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
    query[SessionRow].filter(_.id == sessionIdParam).update(_.currentVideoId -> None)

  inline def updateCurrentAdvertiserQuery = quote:
    (sessionIdParam: user.SessionId, advertiserIdParam: String) =>
      query[SessionRow]
        .filter(_.id == sessionIdParam)
        .update(_.currentAdvertiserId -> Some(advertiserIdParam))

  inline def clearCurrentAdvertiserQuery = quote: (sessionIdParam: user.SessionId) =>
    query[SessionRow].filter(_.id == sessionIdParam).update(_.currentAdvertiserId -> None)

  inline def setAuthorizedQuery = quote:
    (sessionIdParam: user.SessionId, expiresAtParam: String, phaseParam: Phase) =>
      query[SessionRow]
        .filter(_.id == sessionIdParam)
        .update(_.phase -> phaseParam, _.accessExpiresAt -> Some(expiresAtParam))

  /** Authorized sessions of this location that still have a pending expiration. `location_id` comes
    * from the instance: each task serves a single location.
    */
  inline def pendingExpirationsQuery = quote:
    (locationIdParam: Option[String], phaseParam: Phase) =>
      query[SessionRow]
        .filter(s =>
          s.locationId == locationIdParam && s.phase == phaseParam && s.accessExpiresAt.isDefined)
        .map(s => (s.id, s.accessExpiresAt))

  inline def resetForExpirationQuery = quote: (sessionIdParam: user.SessionId, phaseParam: Phase) =>
    query[SessionRow]
      .filter(_.id == sessionIdParam)
      .update(
        _.phase               -> phaseParam,
        _.currentSurveyId     -> None,
        _.currentQuestionId   -> None,
        _.currentVideoId      -> None,
        _.currentAdvertiserId -> None,
        _.accessExpiresAt     -> None)

  /** @param devices
    *   which fingerprint fields make two sessions the same device.
    */
  def apply(
      quill: QuillSqlite,
      locationId: Option[String] = None,
      devices: Policy = Policy.Default): SessionService =
    new SessionService:
      import quill.*

      def findById(sessionId: user.SessionId): Task[Option[SessionData]] =
        run(findByIdQuery(lift(sessionId))).map(_.headOption.map(toSessionData)).orDie

      def matching: Policy = devices

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

      def setAuthorized(sessionId: user.SessionId, expiresAt: java.time.Instant): Task[Unit] =
        run(setAuthorizedQuery(lift(sessionId), lift(expiresAt.toString), lift(Phase.Authorized)))
          .unit
          .orDie

      def findPendingExpirations(): Task[List[(user.SessionId, java.time.Instant)]] =
        run(pendingExpirationsQuery(lift(locationId), lift(Phase.Authorized)))
          .map: rows =>
            rows
              .toList
              .flatMap: (sessionId, expiresAt) =>
                expiresAt.map(raw => sessionId -> java.time.Instant.parse(raw))
          .orDie

  private def toSessionData(row: SessionRow): SessionData =
    val currentQuestion =
      (row.currentSurveyId, row.currentQuestionId) match
        case (Some(surveyId), Some(questionId)) =>
          Some(FullyQualifiedQuestionId(surveyId, questionId))
        case _ =>
          None
    SessionData(
      row.id,
      row.deviceId,
      row.userId,
      row.locale,
      row.phase,
      currentQuestion,
      row.currentVideoId,
      row.currentAdvertiserId.flatMap(AdvertiserId.fromString),
      row.locationId,
      row.clientMac,
      row.apMac,
      row.redirectUrl,
      row.ssid,
      row.fingerprint,
      row.accessExpiresAt.map(java.time.Instant.parse)
    )
  end toSessionData

  val layer: ZLayer[QuillSqlite, Nothing, SessionService] = ZLayer.fromFunction(apply(_, None))

  def layerWithLocation(locationId: Option[String]): ZLayer[QuillSqlite, Nothing, SessionService] =
    ZLayer.fromFunction(apply(_, locationId))
end SessionService
