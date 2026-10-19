package whitelabel.captal.api

import whitelabel.captal.api.AppLayers.FullEnv
import whitelabel.captal.api.ApiBase
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

/** What the server answers, assembled: the API, and in dev the SPA and its assets.
  *
  * Separate from `AppLayers` because the two answer different questions — that one is what the
  * application is built from, this one is what a request reaching port 8080 finds.
  */
private[api] object HttpApp:

  /** How this server serialises, which is part of its wire format and not an implementation detail.
    *
    * zio-http's default sets `ignoreEmptyCollections = true`, so a `List` field with nothing in it
    * disappears from the response. circe's derived decoder has no default to fall back on, so an
    * absent field is a decode failure rather than an empty list: a radio question whose options had
    * all been retired answered 200 with `{"Radio":{}}`, and the SPA could not read it.
    *
    * Three E2E tests caught that and `WireContractSuite` did not, which is the more useful half of
    * the lesson. That suite had been encoding through `JsonCodec.jsonEncoder` with no config, where
    * zio-schema's own default is the opposite — `false` — so it was comparing two libraries through
    * a setting the server does not use, and an empty list looked fine in both. It reads this value
    * now, rather than declaring its own, so the format it checks is the format being served.
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

  /** Under the slug as well as at the root, because `index.html` sets `<base href="/<slug>/">` and
    * its `./brand-icon.svg` resolves against it. In production that is right -- the assets live at
    * `<slug>/` in S3 -- so dev has to answer there too or the icon is a broken image.
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

  /** The linker's output directories, served whole.
    *
    * These used to be four routes naming `main.js` and its source map, from when the client linked
    * to a single file. It does not any more: `ModuleKind.ESModule` with `SmallModulesFor` emits one
    * module per class -- 169 of them at the time of writing -- and `main.js` pulls the rest in by
    * relative path. Naming files here meant every one of those imports 404'd and the client never
    * booted, while `main.js` itself still served 200 and made it look like the build was fine.
    *
    * Both directories, so dev can run against either build: `fastLinkJS.dest` is what `api.dev`
    * renders `index.html` against by default because it links in about a second, and `bundle.dest`
    * is the real artefact -- Vite, terser, hashed chunk names -- for when the question is about the
    * thing production actually serves.
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
    // Also under the slug, because that is where the API is: every route is prefixed with the
    // location slug when one is configured, and `api.dev` always configures one -- so a page served
    // from `/` computes `apiBase = "/api"` (ApiClient takes the first path segment) and every call
    // 404s. Serving it from `/<slug>/` too is what production does: CloudFront puts the portal at
    // `/<slug>/` and the ALB takes `/<slug>/api/*`.
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

  /** Every API route.
    *
    * No slug prefixing here, deliberately — it belongs to each `Endpoint`, and `ApiBase` says at
    * length why. The short version: this function used to call `Routes#nest`, which rewrites the
    * route pattern and not the endpoint's codec, so `/<slug>/api/health` matched a route and then
    * failed to decode. It took starting the server to see that.
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
        devLinkerOutput(
          devStaticRoutes(base.slug) ++ served ++ spaCatchAllRoutes(base.slug))
      else
        served
    baseRoutes @@ loggingMiddleware
  end routes

end HttpApp
