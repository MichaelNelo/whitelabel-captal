package whitelabel.captal.api

import whitelabel.captal.api.ApiBase
import whitelabel.captal.api.AppLayers.FullEnv
import whitelabel.captal.api.routes.{
  AdvertiserSurveyRoutes,
  FinishRoutes,
  HealthRoutes,
  LocaleRoutes,
  SurveyRoutes,
  VideoRoutes
}
import zio.*
import zio.http.*
import zio.http.codec.{CodecConfig, PathCodec}

/** What a request reaching port 8080 finds: the API, and in dev the SPA and its assets. */
private[api] object HttpApp:

  /** How this server serialises — part of its wire format, not an implementation detail.
    *
    * `ignoreEmptyCollections = false` keeps empty collections in the response: circe's derived
    * decoder on the client reads an absent field as a decode failure, not as an empty list. Tests
    * checking the wire format must read this value rather than declare their own.
    */
  val codecConfig: CodecConfig = CodecConfig(ignoreEmptyCollections = false)

  private def loggingMiddleware[R]: Middleware[R] =
    new Middleware[R]:
      def apply[Env1 <: R, Err](routes: Routes[Env1, Err]): Routes[Env1, Err] = routes.transform[
        Env1]: handler =>
        zio
          .http
          .Handler
          .fromFunctionZIO[Request]: request =>
            val startTime = java.lang.System.currentTimeMillis()
            handler
              .runZIO(request)
              .tap: response =>
                val duration = java.lang.System.currentTimeMillis() - startTime
                ZIO.logInfo(
                  s"${request.method} ${request.url.path} -> ${response
                      .status
                      .code} (${duration}ms)")
              .tapError: error =>
                val duration = java.lang.System.currentTimeMillis() - startTime
                ZIO.logError(
                  s"${request.method} ${request.url.path} -> ERROR (${duration}ms): $error")

  /** `assets/styles.css` and `brand-icon.svg`, served at the root and under the slug: `index.html`
    * sets `<base href="/<slug>/">`, so its relative asset URLs resolve under the slug.
    */
  private def devStaticRoutes(locationSlug: Option[String]): Routes[Any, Response] =
    val styles = zio.http.Handler.fromFile(java.io.File("client/assets/styles.css"))
    val icon = zio.http.Handler.fromFile(java.io.File("client/assets/brand-icon.svg"))
    val atRoot = Routes(
      Method.GET / "assets" / "styles.css" -> styles,
      Method.GET / "brand-icon.svg"        -> icon)
    val all =
      locationSlug.fold(atRoot): slug =>
        atRoot ++
          Routes(
            Method.GET / slug / "assets" / "styles.css" -> styles,
            Method.GET / slug / "brand-icon.svg"        -> icon)
    all.handleError(e => Response.internalServerError(e.getMessage))

  /** The linker's output directories served whole, never file by file: `ModuleKind.ESModule` with
    * `SmallModulesFor` emits one module per class and `main.js` pulls the rest in by relative path.
    *
    * Both `fastLinkJS.dest` (what `api.dev` renders `index.html` against) and `bundle.dest` (the
    * Vite/terser artefact production serves), so dev can run against either build.
    */
  private def devLinkerOutput(routes: Routes[FullEnv, Response]): Routes[FullEnv, Response] =
    Seq("fastLinkJS.dest", "bundle.dest").foldLeft(routes): (acc, dir) =>
      acc @@
        Middleware.serveDirectory(
          Path.empty / "out" / "client" / dir,
          java.io.File(s"out/client/$dir"))

  private def spaCatchAllRoutes(locationSlug: Option[String]): Routes[Any, Response] =
    val serveIndex = zio
      .http
      .Handler
      .fromFunctionZIO[Request]: _ =>
        ZIO
          .attemptBlocking(
            java.nio.file.Files.readAllBytes(java.io.File("client/index.html").toPath))
          .map(bytes =>
            Response(
              body = Body.fromArray(bytes),
              headers = Headers(Header.ContentType(MediaType.text.html))))
          .orElse(ZIO.succeed(Response.notFound))
    val atRoot = Routes(
      Method.GET / ""         -> serveIndex,
      Method.GET / "question" -> serveIndex,
      Method.GET / "video"    -> serveIndex,
      Method.GET / "survey"   -> serveIndex,
      Method.GET / "ready"    -> serveIndex
    )
    // Also under the slug: `ApiClient` derives `apiBase` from the first path segment, so a page
    // served from `/` would call `/api/*` while the routes live at `/<slug>/api/*`. Production is
    // the same shape -- CloudFront puts the portal at `/<slug>/`, the ALB takes `/<slug>/api/*`.
    locationSlug.fold(atRoot): slug =>
      atRoot ++
        Routes(
          Method.GET / slug              -> serveIndex,
          Method.GET / slug / "question" -> serveIndex,
          Method.GET / slug / "video"    -> serveIndex,
          Method.GET / slug / "survey"   -> serveIndex,
          Method.GET / slug / "ready"    -> serveIndex
        )
  end spaCatchAllRoutes

  /** Every API route. No slug prefixing here, deliberately — it belongs to each `Endpoint`, as
    * `ApiBase` explains.
    */
  def apiRoutes(
      devEndpoints: Boolean,
      base: ApiBase,
      surveyRoutes: SurveyRoutes,
      localeRoutes: LocaleRoutes,
      videoRoutes: VideoRoutes,
      advertiserSurveyRoutes: AdvertiserSurveyRoutes,
      finishRoutes: FinishRoutes): Routes[FullEnv, Nothing] =
    val mounted =
      HealthRoutes.routes(base) ++ finishRoutes.routes ++ localeRoutes.routes ++
        videoRoutes.routes ++ surveyRoutes.routes ++ advertiserSurveyRoutes.routes
    val all =
      if devEndpoints then
        mounted ++ localeRoutes.devRoutes
      else
        mounted
    all @@ CodecConfig.withConfig(codecConfig)
  end apiRoutes

  /** The API, plus everything a dev server also has to answer for the SPA to run. */
  def routes(
      devMode: Boolean,
      devEndpoints: Boolean,
      base: ApiBase,
      surveyRoutes: SurveyRoutes,
      localeRoutes: LocaleRoutes,
      videoRoutes: VideoRoutes,
      advertiserSurveyRoutes: AdvertiserSurveyRoutes,
      finishRoutes: FinishRoutes): Routes[FullEnv, Response] =
    val served = apiRoutes(
      devEndpoints,
      base,
      surveyRoutes,
      localeRoutes,
      videoRoutes,
      advertiserSurveyRoutes,
      finishRoutes)
    val baseRoutes =
      if devMode then
        devLinkerOutput(devStaticRoutes(base.slug) ++ served ++ spaCatchAllRoutes(base.slug))
      else
        served
    baseRoutes @@ loggingMiddleware
  end routes

end HttpApp
