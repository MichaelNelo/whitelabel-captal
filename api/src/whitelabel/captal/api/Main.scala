package whitelabel.captal.api

import whitelabel.captal.api.routes.{
  AdvertiserSurveyRoutes,
  FinishRoutes,
  LocaleRoutes,
  SurveyRoutes,
  VideoRoutes
}
import whitelabel.captal.api.session.SessionCookieConfig
import whitelabel.captal.platform.session.JobRunner
import zio.*
import zio.http.Server

/** Starts the portal's API, and nothing else: resolve the settings, start the scheduler, log what
  * is mounted, serve.
  */
object Main extends ZIOAppDefault:

  override val run: ZIO[Any, Throwable, Nothing] =
    val program =
      for
        settings  <- ZIO.service[ServerSettings]
        jobRunner <- ZIO.service[JobRunner]
        // A dedicated Scope for the scheduler fibers, provided narrowly: wrapping `program` in
        // `ZIO.scoped` makes the compiler re-infer `R`, collapsing the `Flow.Aux` refinement
        // types into an intersection the ZLayer macro rejects. Never closed — the fibers live as
        // long as the application does.
        schedulerScope <- Scope.make
        _              <- jobRunner.start.provideEnvironment(ZEnvironment[Scope](schedulerScope))
        surveyRoutes   <- ZIO.service[SurveyRoutes]
        localeRoutes   <- ZIO.service[LocaleRoutes]
        videoRoutes    <- ZIO.service[VideoRoutes]
        advertiserSurveyRoutes <- ZIO.service[AdvertiserSurveyRoutes]
        finishRoutes           <- ZIO.service[FinishRoutes]
        cookieConfig           <- ZIO.service[SessionCookieConfig]
        base                   <- ZIO.service[ApiBase]
        served = HttpApp.routes(
          settings.devMode,
          settings.devEndpoints,
          base,
          surveyRoutes,
          localeRoutes,
          videoRoutes,
          advertiserSurveyRoutes,
          finishRoutes)
        // The API half of `served`, enumerated for the log.
        routeInfos =
          HttpApp
            .apiRoutes(
              settings.devEndpoints,
              base,
              surveyRoutes,
              localeRoutes,
              videoRoutes,
              advertiserSurveyRoutes,
              finishRoutes)
            .routes
            .map(route => s"  ${route.routePattern.render}")
            .toList
        _ <- ZIO.logInfo(
          s"Server starting on ${settings.config.address.getHostString}:${settings
              .config
              .address
              .getPort}")
        _ <- ZIO.logInfo(s"Dev mode: ${settings.devMode}, Dev endpoints: ${settings.devEndpoints}")
        _ <- ZIO.logInfo(s"Session cookie: ${cookieConfig.name} (path=${cookieConfig.path})")
        _ <- ZIO.logInfo(s"Mounted routes:\n${routeInfos.mkString("\n")}")
        result <- Server.serve(served)
      yield result
    program.provide(ServerSettings.layer, Server.live, AppLayers.appLayers)
  end run

end Main
