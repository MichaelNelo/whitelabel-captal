package whitelabel.captal.platform.eventhandlers

import java.time.Instant
import java.util.UUID

import io.circe.Json
import io.circe.syntax.*
import io.getquill.*
import whitelabel.captal.core.access.Event as AccessEvent
import whitelabel.captal.core.application.Event
import whitelabel.captal.core.device.Event as DeviceEvent
import whitelabel.captal.core.location.{AccessPoint, Event as LocationEvent, LocationId}
import whitelabel.captal.core.operation.{Event as OperationEvent, RunId}
import whitelabel.captal.core.advertiser.Event as AdvertiserEvent
import whitelabel.captal.core.survey.authoring.Event as AuthoringEvent
import whitelabel.captal.core.survey.question.Id as QuestionId
import whitelabel.captal.core.i18n.Event as I18nEvent
import whitelabel.captal.core.terms.Event as TermsEvent
import whitelabel.captal.core.recovery.Event as RecoveryEvent
import whitelabel.captal.core.session.Event as SessionEvent
import whitelabel.captal.core.survey.Event as SurveyEvent
import whitelabel.captal.core.survey.question.Event as QuestionEvent
import whitelabel.captal.core.survey.question.codecs.given
import whitelabel.captal.core.user.Event as UserEvent
import whitelabel.captal.core.video.authoring.{Event as VideoAuthoringEvent, FileId, VideoKind}
import whitelabel.captal.core.video.{Event as VideoEvent, Id as VideoId, VideoType}
import whitelabel.captal.platform.{Errors, EventLogRow, OperatorContext, VideoStatus}
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.session.SessionContext
import zio.*

object EventLogHandler:
  /** A guest's events, under their session. */
  def apply(ctx: SessionContext): DbEventHandler[Event] =
    new DbEventHandler[Event]:
      def handle(events: List[Event], quill: QuillSqlite): Task[Unit] = ctx
        .getOrFail
        .flatMap: sessionData =>
          write(
            events,
            quill,
            Some(sessionData.sessionId.asString),
            sessionData.userId.map(_.asString),
            None)

  /** Entering the portal's events, under the session they are about. One that entering started is
    * in no context yet, so the session — and its user — is read from the events themselves.
    */
  val forEntry: DbEventHandler[Event] =
    new DbEventHandler[Event]:
      def handle(events: List[Event], quill: QuillSqlite): Task[Unit] =
        val session = events.collectFirst {
          case Event.Session(started: SessionEvent.SessionStarted) =>
            (started.sessionId, started.userId)
          case Event.Session(SessionEvent.WelcomeResolved(sessionId, _, _)) =>
            (sessionId, None)
          case Event.Access(AccessEvent.AccessExpired(sessionId, userId, _)) =>
            (sessionId, userId)
        }
        write(
          events,
          quill,
          session.map(_._1.asString),
          session.flatMap(_._2).map(_.asString),
          None)

  /** An operator's events, under the token that made the change, read from `operators` when they
    * are written: no guest's session. Fails with [[OperatorContext.NotSet]] when nobody was
    * published, rather than log a change under no one.
    */
  def forOperator(operators: OperatorContext): DbEventHandler[Event] =
    new DbEventHandler[Event]:
      def handle(events: List[Event], quill: QuillSqlite): Task[Unit] = operators
        .getOrFail
        .flatMap(operator => write(events, quill, None, None, Some(operator.actor)))

  private def write(
      events: List[Event],
      quill: QuillSqlite,
      sessionId: Option[String],
      userId: Option[String],
      actor: Option[String]): Task[Unit] =
    import quill.*
    for
      instant <- Clock.instant
      now  = instant.toString
      rows = events.flatMap(toEventLogRow(_, sessionId, userId, actor, now))
      _ <- ZIO.foreachDiscard(rows)(row => run(query[EventLogRow].insertValue(lift(row))).orDie)
    yield ()

  private def toEventLogRow(
      event: Event,
      sessionId: Option[String],
      userId: Option[String],
      actor: Option[String],
      now: String): Option[EventLogRow] = extractEventInfo(event).map:
    (eventType, eventData, occurredAt) =>
      EventLogRow(
        id = UUID.randomUUID.toString,
        eventType = eventType,
        eventData = eventData.noSpaces,
        sessionId = sessionId,
        userId = userId,
        occurredAt = occurredAt.toString,
        createdAt = now,
        actor = actor
      )

  private def extractEventInfo(event: Event): Option[(String, Json, Instant)] =
    event match
      case Event.User(e) =>
        extractUserEvent(e)
      case Event.Survey(e) =>
        extractSurveyEvent(e)
      case Event.Video(e) =>
        extractVideoEvent(e)
      case Event.Access(AccessEvent.AccessGranted(sessionId, userId, occurredAt)) =>
        Some(
          (
            "access.granted",
            Json.obj(
              "sessionId"  -> Json.fromString(sessionId.asString),
              "userId"     -> userId.fold(Json.Null)(id => Json.fromString(id.asString)),
              "occurredAt" -> Json.fromString(occurredAt.toString)
            ),
            occurredAt))
      case Event.Access(AccessEvent.AccessExpired(sessionId, userId, occurredAt)) =>
        Some(
          (
            "access.expired",
            Json.obj(
              "sessionId"  -> Json.fromString(sessionId.asString),
              "userId"     -> userId.fold(Json.Null)(id => Json.fromString(id.asString)),
              "occurredAt" -> Json.fromString(occurredAt.toString)
            ),
            occurredAt))
      case Event.Recovery(RecoveryEvent.ErrorRecorded(errorId, _, deviceId, _, _, verdict, at)) =>
        Some(
          (
            "recovery.error_recorded",
            Json.obj(
              "errorId"    -> Json.fromString(errorId.asString),
              "deviceId"   -> Json.fromString(deviceId.asString),
              "verdict"    -> Json.fromString(Errors.verdict(verdict)),
              "occurredAt" -> Json.fromString(at.toString)
            ),
            at))
      case Event.Recovery(RecoveryEvent.RecoveryOffered(errorId, _, availableAt, at)) =>
        Some(
          (
            "recovery.offered",
            Json.obj(
              "errorId"     -> Json.fromString(errorId.asString),
              "availableAt" -> Json.fromString(availableAt.toString),
              "occurredAt"  -> Json.fromString(at.toString)),
            at))
      case Event.Recovery(RecoveryEvent.RecoveryShortened(errorId, _, availableAt, at)) =>
        Some(
          (
            "recovery.shortened",
            Json.obj(
              "errorId"     -> Json.fromString(errorId.asString),
              "availableAt" -> Json.fromString(availableAt.toString),
              "occurredAt"  -> Json.fromString(at.toString)),
            at))
      case Event.Recovery(RecoveryEvent.RecoveryRedeemed(errorId, _, at)) =>
        Some(
          (
            "recovery.redeemed",
            Json.obj(
              "errorId"    -> Json.fromString(errorId.asString),
              "occurredAt" -> Json.fromString(at.toString)),
            at))
      case Event.Device(DeviceEvent.DeviceLinked(userId, deviceId, occurredAt)) =>
        Some(
          (
            "device.linked",
            Json.obj(
              "userId"     -> Json.fromString(userId.asString),
              "deviceId"   -> Json.fromString(deviceId.asString),
              "occurredAt" -> Json.fromString(occurredAt.toString)),
            occurredAt))
      case Event.Device(DeviceEvent.TermsAccepted(deviceId, termsId, occurredAt)) =>
        Some(
          (
            "device.terms_accepted",
            Json.obj(
              "deviceId"   -> Json.fromString(deviceId.asString),
              "termsId"    -> Json.fromString(termsId),
              "occurredAt" -> Json.fromString(occurredAt.toString)),
            occurredAt))
      case Event.Device(DeviceEvent.DeviceRegistered(deviceId, _, signals, occurredAt)) =>
        Some(
          (
            "device.registered",
            Json.obj(
              "deviceId"  -> Json.fromString(deviceId.asString),
              "clientMac" -> signals.clientMac.map(_.show).asJson),
            occurredAt))
      case Event.Device(DeviceEvent.DeviceRecognized(deviceId, _, signals, occurredAt)) =>
        Some(
          (
            "device.recognized",
            Json.obj(
              "deviceId"  -> Json.fromString(deviceId.asString),
              "clientMac" -> signals.clientMac.map(_.show).asJson),
            occurredAt))
      case Event.Session(started: SessionEvent.SessionStarted) =>
        Some(
          (
            "session.started",
            Json.obj(
              "sessionId"  -> Json.fromString(started.sessionId.asString),
              "deviceId"   -> Json.fromString(started.deviceId.asString),
              "userId"     -> started.userId.map(_.asString).asJson,
              "locationId" -> started.locationId.asJson,
              "phase"      -> Json.fromString(started.phase.toString),
              "apMac"      -> started.apMac.map(_.show).asJson
            ),
            started.occurredAt))
      case Event.Session(SessionEvent.WelcomeResolved(sessionId, phase, occurredAt)) =>
        Some(
          (
            "session.welcome_resolved",
            Json.obj(
              "sessionId" -> Json.fromString(sessionId.asString),
              "phase"     -> Json.fromString(phase.toString)),
            occurredAt))
      case Event.Recovery(
            RecoveryEvent.FingerprintMissing(errorId, _, deviceId, location, occurredAt)) =>
        Some(
          (
            "recovery.fingerprint_missing",
            Json.obj(
              "errorId"  -> Json.fromString(errorId.asString),
              "deviceId" -> Json.fromString(deviceId.asString),
              "location" -> location.asJson),
            occurredAt))
      // Which settings the location sets from then on; the values are on its row.
      case Event.Recovery(RecoveryEvent.PolicyChanged(location, policy, occurredAt)) =>
        Some(
          (
            "recovery.policy_changed",
            Json.obj(
              "locationId" -> Json.fromString(location.asString),
              "set"        ->
                Json.fromValues(policy.set.map(setting => Json.fromString(setting.toString)))),
            occurredAt))
      case Event.Terms(e) =>
        Some(extractTermsEvent(e))
      case Event.Advertiser(e) =>
        Some(extractAdvertiserEvent(e))
      case Event.SurveyAuthoring(e) =>
        Some(extractAuthoringEvent(e))
      case Event.VideoAuthoring(e) =>
        Some(extractVideoAuthoringEvent(e))
      // Which keys, not their texts, which are in their own table.
      case Event.I18n(I18nEvent.TranslationsSet(location, locale, entries, occurredAt)) =>
        Some(
          (
            "i18n.translations_set",
            Json.obj(
              "locationId" -> Json.fromString(location.asString),
              "locale"     -> Json.fromString(locale),
              "keys"       -> Json.fromValues(entries.map(entry => Json.fromString(entry.key)))
            ),
            occurredAt))
      case Event.I18n(I18nEvent.TranslationRemoved(location, locale, key, occurredAt)) =>
        Some(
          (
            "i18n.translation_removed",
            Json.obj(
              "locationId" -> Json.fromString(location.asString),
              "locale"     -> Json.fromString(locale),
              "key"        -> Json.fromString(key)),
            occurredAt))
      case Event.Location(e) =>
        Some(extractLocationEvent(e))
      case Event.Operation(e) =>
        Some(extractOperationEvent(e))

  /** What changed in the videos, by id: the texts are in their own table. */
  private def extractVideoAuthoringEvent(event: VideoAuthoringEvent): (String, Json, Instant) =
    def video(id: VideoId) = Json.obj("videoId" -> Json.fromString(id.asString))
    def file(id: VideoId, fileId: FileId) = video(id).deepMerge(
      Json.obj("fileId" -> Json.fromString(fileId.asString)))
    event match
      case VideoAuthoringEvent.VideoRegistered(
            id,
            location,
            kind,
            _,
            _,
            _,
            priority,
            fileId,
            _,
            occurredAt) =>
        val advertiser =
          kind match
            case VideoKind.Advertiser(advertiser, _) =>
              Json.fromString(advertiser.asString)
            case VideoKind.Promo(_) =>
              Json.Null
        (
          "video.registered",
          file(id, fileId).deepMerge(
            Json.obj(
              "locationId"   -> Json.fromString(location.asString),
              "videoType"    -> Json.fromString(VideoType.toDbString(kind.videoType)),
              "advertiserId" -> advertiser,
              "priority"     -> Json.fromInt(priority)
            )),
          occurredAt)
      case VideoAuthoringEvent.FileReserved(id, fileId, _, occurredAt) =>
        ("video.file_reserved", file(id, fileId), occurredAt)
      case VideoAuthoringEvent.FileConfirmed(id, fileId, duration, _, status, occurredAt) =>
        (
          "video.file_confirmed",
          file(id, fileId).deepMerge(
            Json.obj(
              "durationSeconds" -> Json.fromInt(duration),
              "status"          -> Json.fromString(VideoStatus.stored(status)))),
          occurredAt)
      case VideoAuthoringEvent.VideoUpdated(
            id,
            title,
            description,
            minWatchSeconds,
            priority,
            noRepeatSeconds,
            productCampaignId,
            occurredAt) =>
        val fields =
          List(
            title.map(_ => "title"),
            description.map(_ => "description"),
            minWatchSeconds.map(_ => "minWatchSeconds"),
            priority.map(_ => "priority"),
            noRepeatSeconds.map(_ => "noRepeatSeconds"),
            productCampaignId.map(_ => "productCampaignId")
          ).flatten
        (
          "video.updated",
          video(id).deepMerge(Json.obj("fields" -> Json.fromValues(fields.map(Json.fromString)))),
          occurredAt)
      case VideoAuthoringEvent.VideoRetired(id, occurredAt) =>
        ("video.retired", video(id), occurredAt)
      case VideoAuthoringEvent.VideoReinstated(id, occurredAt) =>
        ("video.reinstated", video(id), occurredAt)
    end match
  end extractVideoAuthoringEvent

  /** What changed in the surveys, by id: the wording is in its own table. */
  private def extractAuthoringEvent(event: AuthoringEvent): (String, Json, Instant) =
    def question(id: QuestionId) = Json.obj("questionId" -> Json.fromString(id.asString))
    event match
      case AuthoringEvent.SurveyCreated(id, kind, video, location, _, _, occurredAt) =>
        (
          "survey.created",
          Json.obj(
            "surveyId" -> Json.fromString(id.asString),
            "kind"     -> Json.fromString(kind.toString),
            "videoId"  -> video.fold(Json.Null)(Json.fromString),
            "location" -> location.fold(Json.Null)(Json.fromString)
          ),
          occurredAt)
      case AuthoringEvent.QuestionAdded(survey, added, displayOrder, occurredAt) =>
        (
          "survey.question_added",
          Json.obj(
            "surveyId"     -> Json.fromString(survey.asString),
            "questionId"   -> Json.fromString(added.id.asString),
            "displayOrder" -> Json.fromInt(displayOrder)),
          occurredAt)
      case AuthoringEvent.QuestionUpdated(
            id,
            text,
            description,
            placeholder,
            required,
            points,
            at) =>
        val fields =
          List(
            text.map(_ => "text"),
            description.map(_ => "description"),
            placeholder.map(_ => "placeholder"),
            required.map(_ => "required"),
            points.map(_ => "points")).flatten
        (
          "survey.question_updated",
          question(id).deepMerge(
            Json.obj("fields" -> Json.fromValues(fields.map(Json.fromString)))),
          at)
      case AuthoringEvent.QuestionRetired(id, occurredAt) =>
        ("survey.question_retired", question(id), occurredAt)
      case AuthoringEvent.QuestionReinstated(id, occurredAt) =>
        ("survey.question_reinstated", question(id), occurredAt)
      case AuthoringEvent.QuestionsReordered(survey, order, occurredAt) =>
        (
          "survey.questions_reordered",
          Json.obj(
            "surveyId" -> Json.fromString(survey.asString),
            "order"    -> Json.fromValues(order.map(id => Json.fromString(id.asString)))),
          occurredAt)
    end match
  end extractAuthoringEvent

  private def extractAdvertiserEvent(event: AdvertiserEvent): (String, Json, Instant) =
    event match
      case AdvertiserEvent.AdvertiserCreated(advertiser, occurredAt) =>
        (
          "advertiser.created",
          Json.obj(
            "advertiserId" -> Json.fromString(advertiser.id.asString),
            "name"         -> Json.fromString(advertiser.name),
            "priority"     -> Json.fromInt(advertiser.priority)),
          occurredAt)
      case AdvertiserEvent.AdvertiserUpdated(id, name, priority, occurredAt) =>
        (
          "advertiser.updated",
          Json.obj(
            "advertiserId" -> Json.fromString(id.asString),
            "name"         -> Json.fromString(name),
            "priority"     -> Json.fromInt(priority)),
          occurredAt)
      case AdvertiserEvent.AdvertiserRetired(id, occurredAt) =>
        ("advertiser.retired", Json.obj("advertiserId" -> Json.fromString(id.asString)), occurredAt)
      case AdvertiserEvent.AdvertiserReinstated(id, occurredAt) =>
        (
          "advertiser.reinstated",
          Json.obj("advertiserId" -> Json.fromString(id.asString)),
          occurredAt)

  /** The terms' events, which locales they touch: the texts are in their own table. */
  private def extractTermsEvent(event: TermsEvent): (String, Json, Instant) =
    event match
      case TermsEvent.VersionPublished(id, version, translations, occurredAt) =>
        (
          "terms.version_published",
          Json.obj(
            "termsId" -> Json.fromString(id),
            "version" -> Json.fromInt(version),
            "locales" -> Json.fromValues(translations.map(t => Json.fromString(t.locale)))),
          occurredAt)
      case TermsEvent.TranslationSet(terms, translation, occurredAt) =>
        (
          "terms.translation_set",
          Json.obj(
            "termsId" -> Json.fromString(terms),
            "locale"  -> Json.fromString(translation.locale)),
          occurredAt)
      case TermsEvent.TranslationRemoved(terms, locale, occurredAt) =>
        (
          "terms.translation_removed",
          Json.obj("termsId" -> Json.fromString(terms), "locale" -> Json.fromString(locale)),
          occurredAt)

  /** A location's events, its access points' tokens left out: whether one is set is all the log
    * says of them.
    */
  private def extractLocationEvent(event: LocationEvent): (String, Json, Instant) =
    event match
      case LocationEvent.LocationCreated(id, slug, name, occurredAt) =>
        (
          "location.created",
          Json.obj(
            "locationId" -> Json.fromString(id.asString),
            "slug"       -> Json.fromString(slug.asString),
            "name"       -> Json.fromString(name)),
          occurredAt)
      case LocationEvent.FingerprintRequirementChanged(id, required, occurredAt) =>
        (
          "location.fingerprint_requirement_changed",
          Json.obj(
            "locationId" -> Json.fromString(id.asString),
            "required"   -> Json.fromBoolean(required)),
          occurredAt)
      case LocationEvent.AccessPointRegistered(location, point, occurredAt) =>
        (
          "location.access_point_registered",
          Json.obj(
            "locationId"  -> Json.fromString(location.asString),
            "accessPoint" -> accessPointJson(point)),
          occurredAt)
      case LocationEvent.AccessPointUpdated(location, mac, point, occurredAt) =>
        (
          "location.access_point_updated",
          Json.obj(
            "locationId"  -> Json.fromString(location.asString),
            "mac"         -> Json.fromString(mac.show),
            "accessPoint" -> accessPointJson(point)),
          occurredAt)
      case LocationEvent.AccessPointRemoved(location, mac, occurredAt) =>
        (
          "location.access_point_removed",
          Json.obj(
            "locationId" -> Json.fromString(location.asString),
            "mac"        -> Json.fromString(mac.show)),
          occurredAt)
      case LocationEvent.LocationDeployed(id, by, occurredAt) =>
        ("location.deployed", transitionJson(id, by), occurredAt)
      case LocationEvent.LocationPaused(id, by, occurredAt) =>
        ("location.paused", transitionJson(id, by), occurredAt)
      case LocationEvent.LocationResumed(id, by, occurredAt) =>
        ("location.resumed", transitionJson(id, by), occurredAt)
      case LocationEvent.LocationDestroyed(id, by, occurredAt) =>
        ("location.destroyed", transitionJson(id, by), occurredAt)
      // How large, not what: the document itself is in the assets bucket.
      case LocationEvent.StylesChanged(id, _, css, occurredAt) =>
        ("location.styles_changed", documentJson(id, css), occurredAt)
      case LocationEvent.IconChanged(id, _, svg, occurredAt) =>
        ("location.icon_changed", documentJson(id, svg), occurredAt)

  private def documentJson(id: LocationId, content: String): Json = Json.obj(
    "locationId" -> Json.fromString(id.asString),
    "bytes"      -> Json.fromInt(content.getBytes("UTF-8").length))

  private def transitionJson(id: LocationId, by: RunId): Json = Json.obj(
    "locationId" -> Json.fromString(id.asString),
    "run"        -> Json.fromString(by.asString))

  /** An operation's start and end. Its steps are on the run's row as they happen, not here. */
  private def extractOperationEvent(event: OperationEvent): (String, Json, Instant) =
    event match
      case OperationEvent.OperationStarted(
            run,
            location,
            slug,
            operation,
            steps,
            finishing,
            _,
            at) =>
        (
          "operation.started",
          Json.obj(
            "run"        -> Json.fromString(run.asString),
            "locationId" -> Json.fromString(location.asString),
            "slug"       -> Json.fromString(slug.asString),
            "operation"  -> Json.fromString(operation.name),
            "steps"      -> Json.fromValues(steps.map(step => Json.fromString(step.toString))),
            "finishing"  -> finishing.fold(Json.Null)(id => Json.fromString(id.asString))
          ),
          at)
      case OperationEvent.OperationCompleted(run, location, at) =>
        (
          "operation.completed",
          Json.obj(
            "run"        -> Json.fromString(run.asString),
            "locationId" -> Json.fromString(location.asString)),
          at)
      case OperationEvent.OperationFailed(run, location, step, failure, at) =>
        (
          "operation.failed",
          Json.obj(
            "run"        -> Json.fromString(run.asString),
            "locationId" -> Json.fromString(location.asString),
            "step"       -> Json.fromString(step.toString),
            "failure"    -> Json.fromString(failure.detail)
          ),
          at)

  private def accessPointJson(point: AccessPoint): Json = Json.obj(
    "mac"             -> Json.fromString(point.mac.show),
    "host"            -> Json.fromString(point.controller.host),
    "port"            -> point.controller.port.asJson,
    "siteId"          -> Json.fromString(point.controller.siteId),
    "durationMinutes" -> point.settings.durationMinutes.asJson,
    "redirectUrl"     -> point.settings.redirectUrl.asJson,
    "unifiEnabled"    -> Json.fromBoolean(point.settings.unifiEnabled)
  )

  private def extractUserEvent(event: UserEvent): Option[(String, Json, Instant)] =
    event match
      case UserEvent.UserCreated(userId, email, occurredAt) =>
        Some(
          (
            "user.created",
            Json.obj(
              "userId"     -> Json.fromString(userId.asString),
              "email"      -> Json.fromString(email.value),
              "occurredAt" -> Json.fromString(occurredAt.toString)),
            occurredAt))
      case UserEvent.UserRecognized(userId, email, occurredAt) =>
        Some(
          (
            "user.recognized",
            Json.obj(
              "userId"     -> Json.fromString(userId.asString),
              "email"      -> Json.fromString(email.value),
              "occurredAt" -> Json.fromString(occurredAt.toString)),
            occurredAt))
      case UserEvent.NewUserArrived(userId, nextQuestion, occurredAt) =>
        Some(
          (
            "user.new_arrived",
            Json.obj(
              "userId"       -> Json.fromString(userId.asString),
              "nextQuestion" ->
                nextQuestion
                  .map(q =>
                    Json.obj(
                      "surveyId"   -> Json.fromString(q.surveyId.asString),
                      "questionId" -> Json.fromString(q.questionId.asString)))
                  .getOrElse(Json.Null),
              "occurredAt" -> Json.fromString(occurredAt.toString)
            ),
            occurredAt))
      case UserEvent.SurveyAssigned(userId, nextQuestion, occurredAt) =>
        Some(
          (
            "user.survey_assigned",
            Json.obj(
              "userId"       -> Json.fromString(userId.asString),
              "nextQuestion" ->
                Json.obj(
                  "surveyId"   -> Json.fromString(nextQuestion.surveyId.asString),
                  "questionId" -> Json.fromString(nextQuestion.questionId.asString)),
              "occurredAt" -> Json.fromString(occurredAt.toString)
            ),
            occurredAt))
      case UserEvent.IdentificationCompleted(userId, occurredAt) =>
        Some(
          (
            "user.identification_completed",
            Json.obj(
              "userId"     -> Json.fromString(userId.asString),
              "occurredAt" -> Json.fromString(occurredAt.toString)),
            occurredAt))
      case UserEvent.VideoAssigned(userId, videoId, advertiserId, videoType, occurredAt) =>
        Some(
          (
            "user.video_assigned",
            Json.obj(
              "userId"       -> Json.fromString(userId.asString),
              "videoId"      -> Json.fromString(videoId.asString),
              "advertiserId" -> advertiserId.fold(Json.Null)(id => Json.fromString(id.asString)),
              "videoType"    -> Json.fromString(VideoType.toDbString(videoType)),
              "occurredAt"   -> Json.fromString(occurredAt.toString)
            ),
            occurredAt))
      case UserEvent.VideoSurveyAssigned(userId, advertiserId, nextQuestion, occurredAt) =>
        Some(
          (
            "user.video_survey_assigned",
            Json.obj(
              "userId" -> Json.fromString(userId.asString),
              // Null for a promo's survey, the same way `user.video_assigned` above reports a
              // promo's video.
              "advertiserId" -> advertiserId.fold(Json.Null)(id => Json.fromString(id.asString)),
              "nextQuestion" ->
                Json.obj(
                  "surveyId"   -> Json.fromString(nextQuestion.surveyId.asString),
                  "questionId" -> Json.fromString(nextQuestion.questionId.asString)),
              "occurredAt" -> Json.fromString(occurredAt.toString)
            ),
            occurredAt))
      case UserEvent.UserFinishedProcess(userId, videoId, answeredQuestionIds, occurredAt) =>
        Some(
          (
            "user.finished_process",
            Json.obj(
              "userId"              -> Json.fromString(userId.asString),
              "videoId"             -> videoId.fold(Json.Null)(id => Json.fromString(id.asString)),
              "answeredQuestionIds" ->
                Json.arr(
                  answeredQuestionIds.map(q =>
                    Json.obj(
                      "surveyId"   -> Json.fromString(q.surveyId.asString),
                      "questionId" -> Json.fromString(q.questionId.asString)))*),
              "occurredAt" -> Json.fromString(occurredAt.toString)
            ),
            occurredAt))

  private def extractSurveyEvent(event: SurveyEvent): Option[(String, Json, Instant)] =
    event match
      case SurveyEvent.QuestionAnswered(surveyId, questionEvent) =>
        questionEvent match
          case QuestionEvent.EmailQuestionAnswered(userId, _, questionId, answer, occurredAt) =>
            Some(
              (
                "survey.email_answered",
                Json.obj(
                  "userId"     -> Json.fromString(userId.asString),
                  "surveyId"   -> Json.fromString(surveyId.asString),
                  "questionId" -> Json.fromString(questionId.asString),
                  "answer"     -> answer.value.asJson,
                  "occurredAt" -> Json.fromString(occurredAt.toString)
                ),
                occurredAt))
          case QuestionEvent.ProfilingQuestionAnswered(userId, _, questionId, answer, occurredAt) =>
            Some(
              (
                "survey.profiling_answered",
                Json.obj(
                  "userId"     -> Json.fromString(userId.asString),
                  "surveyId"   -> Json.fromString(surveyId.asString),
                  "questionId" -> Json.fromString(questionId.asString),
                  "answer"     -> answer.value.asJson,
                  "occurredAt" -> Json.fromString(occurredAt.toString)
                ),
                occurredAt))
          case QuestionEvent.LocationQuestionAnswered(
                userId,
                _,
                questionId,
                hierarchyLevel,
                answer,
                occurredAt) =>
            Some(
              (
                "survey.location_answered",
                Json.obj(
                  "userId"         -> Json.fromString(userId.asString),
                  "surveyId"       -> Json.fromString(surveyId.asString),
                  "questionId"     -> Json.fromString(questionId.asString),
                  "hierarchyLevel" -> hierarchyLevel.asJson,
                  "answer"         -> answer.value.asJson,
                  "occurredAt"     -> Json.fromString(occurredAt.toString)
                ),
                occurredAt))
          case QuestionEvent.AdvertiserQuestionAnswered(
                userId,
                _,
                advertiserId,
                questionId,
                answer,
                occurredAt) =>
            Some(
              (
                "survey.advertiser_answered",
                Json.obj(
                  "userId"   -> Json.fromString(userId.asString),
                  "surveyId" -> Json.fromString(surveyId.asString),
                  // Null when the question followed a promo, which has no advertiser to attribute
                  // the answer to.
                  "advertiserId" ->
                    advertiserId.fold(Json.Null)(id => Json.fromString(id.asString)),
                  "questionId" -> Json.fromString(questionId.asString),
                  "answer"     -> answer.value.asJson,
                  "occurredAt" -> Json.fromString(occurredAt.toString)
                ),
                occurredAt))

  private def extractVideoEvent(event: VideoEvent): Option[(String, Json, Instant)] =
    event match
      case VideoEvent.VideoVisualized(
            sessionId,
            userId,
            videoId,
            advertiserId,
            videoType,
            durationWatched,
            completed,
            occurredAt) =>
        Some(
          (
            "video.visualized",
            Json.obj(
              "sessionId"       -> Json.fromString(sessionId.asString),
              "userId"          -> userId.fold(Json.Null)(id => Json.fromString(id.asString)),
              "videoId"         -> Json.fromString(videoId.asString),
              "advertiserId"    -> advertiserId.fold(Json.Null)(id => Json.fromString(id.asString)),
              "videoType"       -> Json.fromString(VideoType.toDbString(videoType)),
              "durationWatched" -> Json.fromInt(durationWatched),
              "completed"       -> Json.fromBoolean(completed),
              "occurredAt"      -> Json.fromString(occurredAt.toString)
            ),
            occurredAt))
      // `video_playbacks` is their record; see `PlaybackHandler`.
      case _: VideoEvent.PlaybackStarted | _: VideoEvent.PlaybackEnded =>
        None
end EventLogHandler
