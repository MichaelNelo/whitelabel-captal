package whitelabel.captal.api

import io.getquill.jdbczio.Quill
import whitelabel.captal.api.routes.{
  AdvertiserSurveyRoutes,
  FinishRoutes,
  LocaleRoutes,
  SurveyRoutes,
  VideoRoutes
}
import whitelabel.captal.api.session.{
  CurrentLocation,
  SessionCookieConfig,
  UserCookieConfig,
  UserLookup
}
import whitelabel.captal.platform.repositories.{
  SurveyRepositoryQuill,
  UserRepositoryQuill,
  VideoRepositoryQuill
}
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.services.{LocaleService, LocaleServiceQuill, LocationService}
import whitelabel.captal.platform.session.{JobQueue, JobRunner, SessionContext, SessionService}
import whitelabel.captal.platform.{RqliteDataSource, UnifiAccess, UnifiClient}
import zio.*
import zio.http.{Client, Server}

/** What the application is made of, and in what order it can be built: the layers needing
  * `ServerSettings` to resolve a location, the plain wiring, `appLayers` composing them all, and
  * `FullEnv` — what that composition produces.
  */
private[api] object AppLayers:

  /** Everything a request handler can ask for. `JobRunner` is deliberately absent: `Main.run`
    * consumes it to start the scheduler fibers, and no endpoint needs it.
    */
  type FullEnv =
    SessionContext & SessionService & LocaleService & QuillSqlite & SessionCookieConfig & ApiBase &
      CurrentLocation & UserCookieConfig & UserLookup & SurveyRoutes & LocaleRoutes & VideoRoutes &
      AdvertiserSurveyRoutes & FinishRoutes & SurveyRoutes.AnswerEmailFlowType &
      SurveyRoutes.AnswerProfilingFlowType & SurveyRoutes.AnswerLocationFlowType &
      SurveyRoutes.NextSurveyFlowType & VideoRoutes.NextVideoFlowType &
      VideoRoutes.MarkVideoWatchedFlowType & AdvertiserSurveyRoutes.NextAdvertiserSurveyFlowType &
      AdvertiserSurveyRoutes.AnswerAdvertiserFlowType & FinishRoutes.FinishFlowType

  // ─── Location-aware ───────────────────────────────────────────────────────────

  private def resolveLocationId: ZIO[ServerSettings & LocationService, Throwable, Option[String]] =
    ZIO.serviceWithZIO[ServerSettings]: settings =>
      settings.locationSlug match
        case None =>
          ZIO.succeed(None)
        case Some(slug) =>
          LocationService
            .resolveSlug(slug)
            .tapError(e => ZIO.logError(s"Configuration error: ${e.getMessage}"))
            .tap(id => ZIO.logInfo(s"Resolved location slug '$slug' -> $id"))
            .map(Some(_))

  private val sessionServiceLayer
      : ZLayer[QuillSqlite & LocationService & ServerSettings, Throwable, SessionService] = ZLayer
    .fromZIO:
      for
        quill      <- ZIO.service[QuillSqlite]
        locationId <- resolveLocationId
      yield SessionService(quill, locationId)

  private val localeServiceLayer
      : ZLayer[QuillSqlite & LocationService & ServerSettings, Throwable, LocaleService] = ZLayer
    .fromZIO:
      for
        quill      <- ZIO.service[QuillSqlite]
        locationId <- resolveLocationId
      yield LocaleServiceQuill(quill, locationId)

  /** Per-location cookie naming and path, so two locations on one domain stay isolated. */
  private val sessionCookieConfigLayer: ZLayer[ServerSettings, Nothing, SessionCookieConfig] =
    ZLayer.fromFunction((s: ServerSettings) => SessionCookieConfig.fromSlug(s.locationSlug))

  /** Trust-all TLS plus an optional proxy, for reaching a UniFi controller. */
  private val unifiHttpClientLayer: ZLayer[ServerSettings, Throwable, Client] = ZLayer
    .service[ServerSettings]
    .flatMap(env => UnifiClient.trustAllClientLayer(env.get.unifiProxyUrl))

  /** Snapshot of the location this task serves, for soft-validating the AP MAC and the like. */
  private val currentLocationLayer
      : ZLayer[ServerSettings & LocationService, Throwable, CurrentLocation] = ZLayer.fromZIO:
    for
      settings <- ZIO.service[ServerSettings]
      locSvc   <- ZIO.service[LocationService]
      cl       <-
        settings.locationSlug match
          case None =>
            ZIO.succeed(CurrentLocation.empty)
          case Some(s) =>
            locSvc
              .findBySlug(s)
              .someOrFail(LocationService.LocationNotFound(s))
              .map: row =>
                CurrentLocation(
                  Some(row.id),
                  Some(row.slug),
                  row.unifiApMac,
                  UnifiAccess.fromRow(row))
    yield cl

  // ─── Composition ──────────────────────────────────────────────────────────────

  /** Reads the locations the backoffice writes, and provisions nothing: starting a container is
    * never a write to the database. `LOCATION_SLUG` names which location this task serves.
    */
  private val locationServiceLayer: ZLayer[QuillSqlite, Nothing, LocationService] = ZLayer.fromZIO:
    ZIO.serviceWith[QuillSqlite](LocationService(_))

  val appLayers: ZLayer[ServerSettings, Throwable, FullEnv & Server.Config & JobRunner] = ZLayer
    .makeSome[ServerSettings, FullEnv & Server.Config & JobRunner](
      SessionContext.make,
      RqliteDataSource.layer,
      Quill.Sqlite.fromNamingStrategy(io.getquill.SnakeCase),
      locationServiceLayer,
      sessionServiceLayer,
      localeServiceLayer,
      sessionCookieConfigLayer,
      ApiBase.layer,
      currentLocationLayer,
      UserCookieConfig.layer,
      UserLookup.layer,
      SurveyRoutes.layer,
      LocaleRoutes.layer,
      VideoRoutes.layer,
      AdvertiserSurveyRoutes.layer,
      FinishRoutes.layer,
      SurveyRepositoryQuill.layer,
      UserRepositoryQuill.layer,
      VideoRepositoryQuill.layer,
      unifiHttpClientLayer,
      UnifiClient.layer,
      Flows.eventHandlerLayer,
      Flows.answerEmailFlowLayer,
      Flows.answerProfilingFlowLayer,
      Flows.answerLocationFlowLayer,
      Flows.nextSurveyFlowLayer,
      Flows.nextVideoFlowLayer,
      Flows.markVideoWatchedFlowLayer,
      Flows.nextAdvertiserSurveyFlowLayer,
      Flows.answerAdvertiserFlowLayer,
      Flows.finishFlowLayer,
      Flows.expireAccessFlowLayer,
      JobQueue.layer,
      JobRunner.layer,
      ZLayer.fromFunction((s: ServerSettings) => s.config)
    )

end AppLayers
