package whitelabel.captal.platform.session

import io.getquill.*
import whitelabel.captal.core.application.Phase
import whitelabel.captal.core.device.{Matching, Policy, Signals}
import whitelabel.captal.core.infrastructure.SessionData
import whitelabel.captal.core.survey.AdvertiserId
import whitelabel.captal.core.survey.question.FullyQualifiedQuestionId
import whitelabel.captal.core.{survey, user, video}
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.core.given
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.{DeviceRow, SessionRow}
import zio.*

final case class CaptivePortalParams(
    clientMac: String,
    apMac: String,
    redirectUrl: String,
    ssid: String,
    fingerprint: String)

trait SessionService:
  def findById(sessionId: user.SessionId): Task[Option[SessionData]]
  def create(
      userAgent: String,
      locale: String,
      phase: Phase,
      portalParams: CaptivePortalParams): Task[SessionData]

  /** Same as [[create]] but pre-populates `userId` so a returning user (recognized via the
    * cross-location `captal_user` cookie) can be linked to their existing answers from the start.
    */
  def createForUser(
      userAgent: String,
      locale: String,
      phase: Phase,
      portalParams: CaptivePortalParams,
      userId: user.Id): Task[SessionData]

  def setPhase(sessionId: user.SessionId, phase: Phase): Task[Unit]
  def setLocale(sessionId: user.SessionId, locale: String): Task[Unit]
  def setCurrentQuestion(sessionId: user.SessionId, question: FullyQualifiedQuestionId): Task[Unit]
  def clearCurrentQuestion(sessionId: user.SessionId): Task[Unit]

  /** Mark the session as authorized (Phase.Authorized) until the given instant. */
  def setAuthorized(sessionId: user.SessionId, expiresAt: java.time.Instant): Task[Unit]

  /** Reset a session whose access has expired: phase back to Welcome, clear the fields of the visit
    * that ended (current survey/question/video/advertiser, access_expires_at). The session row is
    * kept so the cookie continues to resolve, and so is its user: the phone's next visit is that
    * guest's, asked only what they have not answered.
    */
  def resetForExpiration(sessionId: user.SessionId): Task[Unit]

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

      /** The device the redirect's signals are, found by the database with the configured policy; a
        * new one when none matches, or when the signals lack what the policy compares.
        */
      private def resolveDevice(
          portalParams: CaptivePortalParams,
          userAgent: String,
          now: String): Task[user.DeviceId] =
        val seen = Signals(portalParams.clientMac, portalParams.fingerprint)
        val found: Task[Option[user.DeviceId]] =
          if !devices.covers(seen) then
            ZIO.none
          else
            devices match
              case Policy.ByFingerprint =>
                deviceMatching(Matching.ByFingerprint, seen)
              case Policy.ByMac =>
                deviceMatching(Matching.ByMac, seen)
              case Policy.ByMacAndFingerprint =>
                deviceMatching(Matching.ByMacAndFingerprint, seen)
        found
          .flatMap:
            case Some(deviceId) =>
              // The fields the policy did not compare move with the device: a MAC randomized per
              // network, a fingerprint the shortener computed anew.
              run(
                query[DeviceRow]
                  .filter(_.id == lift(deviceId))
                  .update(
                    _.userAgent   -> lift(userAgent),
                    _.clientMac   -> lift(seen.clientMac),
                    _.fingerprint -> lift(seen.fingerprint),
                    _.updatedAt   -> lift(now))).as(deviceId)
            case None =>
              val row = DeviceRow(
                id = user.DeviceId.generate,
                userAgent = userAgent,
                createdAt = now,
                updatedAt = now,
                clientMac = seen.clientMac,
                fingerprint = seen.fingerprint)
              run(query[DeviceRow].insertValue(lift(row))).as(row.id)
          .orDie
      end resolveDevice

      private inline def deviceMatching(
          inline m: Matching,
          seen: Signals): Task[Option[user.DeviceId]] = run(
        query[DeviceRow]
          .filter(d =>
            m.matches(
              Signals(d.clientMac, d.fingerprint),
              Signals(lift(seen.clientMac), lift(seen.fingerprint))))
          .map(_.id)
          .take(1)).map(_.headOption)

      def create(
          userAgent: String,
          locale: String,
          phase: Phase,
          portalParams: CaptivePortalParams): Task[SessionData] =
        for
          instant <- Clock.instant
          now       = instant.toString
          sessionId = user.SessionId.generate
          deviceId <- resolveDevice(portalParams, userAgent, now)
          sessionRow = SessionRow(
            id = sessionId,
            userId = None,
            deviceId = deviceId,
            locale = locale,
            phase = phase,
            currentSurveyId = None,
            currentQuestionId = None,
            currentVideoId = None,
            currentAdvertiserId = None,
            locationId = locationId,
            createdAt = now,
            clientMac = portalParams.clientMac,
            apMac = portalParams.apMac,
            redirectUrl = portalParams.redirectUrl,
            ssid = portalParams.ssid,
            fingerprint = portalParams.fingerprint
          )
          _ <-
            run(
              query[SessionRow].insert(
                _.id                  -> lift(sessionRow.id),
                _.userId              -> lift(sessionRow.userId),
                _.deviceId            -> lift(sessionRow.deviceId),
                _.locale              -> lift(sessionRow.locale),
                _.phase               -> lift(sessionRow.phase),
                _.currentSurveyId     -> lift(sessionRow.currentSurveyId),
                _.currentQuestionId   -> lift(sessionRow.currentQuestionId),
                _.currentVideoId      -> lift(sessionRow.currentVideoId),
                _.currentAdvertiserId -> lift(sessionRow.currentAdvertiserId),
                _.locationId          -> lift(sessionRow.locationId),
                _.createdAt           -> lift(sessionRow.createdAt),
                _.clientMac           -> lift(sessionRow.clientMac),
                _.apMac               -> lift(sessionRow.apMac),
                _.redirectUrl         -> lift(sessionRow.redirectUrl),
                _.ssid                -> lift(sessionRow.ssid),
                _.fingerprint         -> lift(sessionRow.fingerprint)
              )).orDie
        yield SessionData(
          sessionId,
          deviceId,
          None,
          locale,
          phase,
          None,
          None,
          None,
          locationId = locationId,
          clientMac = portalParams.clientMac,
          apMac = portalParams.apMac,
          redirectUrl = portalParams.redirectUrl,
          ssid = portalParams.ssid,
          fingerprint = portalParams.fingerprint
        )
      end create

      def createForUser(
          userAgent: String,
          locale: String,
          phase: Phase,
          portalParams: CaptivePortalParams,
          userId: user.Id): Task[SessionData] =
        for
          instant <- Clock.instant
          now       = instant.toString
          sessionId = user.SessionId.generate
          deviceId <- resolveDevice(portalParams, userAgent, now)
          sessionRow = SessionRow(
            id = sessionId,
            userId = Some(userId),
            deviceId = deviceId,
            locale = locale,
            phase = phase,
            currentSurveyId = None,
            currentQuestionId = None,
            currentVideoId = None,
            currentAdvertiserId = None,
            locationId = locationId,
            createdAt = now,
            clientMac = portalParams.clientMac,
            apMac = portalParams.apMac,
            redirectUrl = portalParams.redirectUrl,
            ssid = portalParams.ssid,
            fingerprint = portalParams.fingerprint
          )
          _ <-
            run(
              query[SessionRow].insert(
                _.id                  -> lift(sessionRow.id),
                _.userId              -> lift(sessionRow.userId),
                _.deviceId            -> lift(sessionRow.deviceId),
                _.locale              -> lift(sessionRow.locale),
                _.phase               -> lift(sessionRow.phase),
                _.currentSurveyId     -> lift(sessionRow.currentSurveyId),
                _.currentQuestionId   -> lift(sessionRow.currentQuestionId),
                _.currentVideoId      -> lift(sessionRow.currentVideoId),
                _.currentAdvertiserId -> lift(sessionRow.currentAdvertiserId),
                _.locationId          -> lift(sessionRow.locationId),
                _.createdAt           -> lift(sessionRow.createdAt),
                _.clientMac           -> lift(sessionRow.clientMac),
                _.apMac               -> lift(sessionRow.apMac),
                _.redirectUrl         -> lift(sessionRow.redirectUrl),
                _.ssid                -> lift(sessionRow.ssid),
                _.fingerprint         -> lift(sessionRow.fingerprint)
              )).orDie
        yield SessionData(
          sessionId,
          deviceId,
          Some(userId),
          locale,
          phase,
          None,
          None,
          None,
          locationId = locationId,
          clientMac = portalParams.clientMac,
          apMac = portalParams.apMac,
          redirectUrl = portalParams.redirectUrl,
          ssid = portalParams.ssid,
          fingerprint = portalParams.fingerprint
        )
      end createForUser

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

      def resetForExpiration(sessionId: user.SessionId): Task[Unit] =
        run(resetForExpirationQuery(lift(sessionId), lift(Phase.Welcome))).unit.orDie

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
