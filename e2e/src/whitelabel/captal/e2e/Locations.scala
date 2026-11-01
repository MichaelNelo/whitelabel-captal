package whitelabel.captal.e2e

import scala.collection.concurrent.TrieMap

import whitelabel.captal.core.i18n.{I18n, I18nSchema}
import whitelabel.captal.mcp.tools.{
  AdvertiserTools,
  AnswerFormat,
  DeployTools,
  I18nTools,
  IdentificationCategory,
  LocalizedText,
  LocationTools,
  SurveyTools,
  UnifiTools,
  VideoTools
}

/** The locations the features stand up through the backoffice, as an operator would, and deploy.
  * One per name for the whole run, so every scenario that names it shares it: a deploy takes
  * minutes. Each run creates its own, under a slug of its own, in the local configuration's
  * database, which other runs and a developer's own content share.
  *
  * Their UniFi settings point at the local configuration's stand-in for a controller ([[Venue]]),
  * entered through the page `configure_unifi` opens, as an operator enters them.
  */
object Locations:
  final case class Deployed(slug: String, name: String):
    /** The portal as UniFi sends a guest to it, from the device `mac` behind the access point. */
    def portal(mac: String, clickId: String): String =
      val back = java.net.URLEncoder.encode("http://example.com", "UTF-8")
      s"${Run.setting(
          "E2E_PORTAL")}/$slug/?id=$mac&ap=AA:BB:CC:DD:EE:01&ssid=E2E&url=$back&click_id=$clickId"

    def api(path: String): String = s"${Run.setting("E2E_PORTAL")}/$slug/api/$path"

  /** Every text a guest sees, in both locales, as `text <key>`: a step finds a button by its key.
    */
  val Locales = List("en", "es")

  def text(key: String): String = s"text $key"

  private val deployed = TrieMap.empty[String, Deployed]

  /** `name`, with an advertiser video and the survey asked after it, deployed. */
  def withAdvertiserVideo(name: String): Deployed = deployed.getOrElseUpdate(name, create(name))

  private def create(name: String): Deployed =
    val run = scala.util.Random.alphanumeric.filter(_.isLetterOrDigit).take(4).mkString.toLowerCase
    val location = Deployed(s"e2e-$name-$run", s"E2E $name")
    import location.slug
    Backoffice.tool[LocationTools.CreateRequest, LocationTools.CreateResponse](
      "create_location",
      LocationTools.CreateRequest(slug, location.name))
    configureUnifi(slug)
    ensureIdentification()
    val advertiser =
      Backoffice
        .tool[AdvertiserTools.CreateRequest, AdvertiserTools.CreateResponse](
          "create_advertiser",
          AdvertiserTools.CreateRequest(s"E2E advertiser $slug", 10))
        .id
    val video = Backoffice.tool[VideoTools.CreateVideoRequest, VideoTools.CreateVideoResponse](
      "create_advertiser_video",
      VideoTools.CreateVideoRequest(
        slug = slug,
        advertiserId = advertiser,
        title = texts("E2E spot"),
        description = None,
        minWatchSeconds = None,
        priority = Some(1),
        questions = List(
          VideoTools.VideoQuestion(
            text = texts("Did you like it?"),
            answer = AnswerFormat.Input(None),
            required = Some(true),
            points = Some(1))),
        productCampaignId = None
      )
    )
    upload(video.uploadUrl)
    Backoffice.tool[VideoTools.ConfirmUploadRequest, VideoTools.ConfirmUploadResponse](
      "confirm_video_upload",
      VideoTools.ConfirmUploadRequest(video.videoId, 6))
    Locales.foreach: locale =>
      Backoffice.tool[I18nTools.SetTranslationsRequest, I18nTools.SetTranslationsResponse](
        "set_translations",
        I18nTools.SetTranslationsRequest(
          slug = slug,
          locale = locale,
          entries = I18nSchema.keys[I18n].map(key => I18nTools.Translation(key, text(key))))
      )
    Backoffice.awaitRun(
      Backoffice.tool[DeployTools.DeployRequest, DeployTools.RunView](
        "deploy_location",
        DeployTools.DeployRequest(slug)))
    Portal.awaitServing(location)
    location
  end create

  private def texts(value: String) = Locales.map(LocalizedText(_, value))

  /** What the page `configure_unifi` opens does: posts the controller's settings with the ticket
    * the tool handed it. Then the calls are turned on, which a new location has by default anyway.
    */
  private def configureUnifi(slug: String): Unit =
    val page = Backoffice.tool[UnifiTools.ConfigureRequest, UnifiTools.ConfigureResponse](
      "configure_unifi",
      UnifiTools.ConfigureRequest(slug))
    val form = Map(
      "ticket"          -> page.ticket,
      "host"            -> Run.setting("E2E_UNIFI_HOST"),
      "port"            -> Run.setting("E2E_UNIFI_PORT"),
      "siteId"          -> Venue.Site,
      "apiToken"        -> Venue.ApiKey,
      "durationMinutes" -> Venue.AccessMinutes.toString
    )
    val body = form
      .map((name, value) =>
        s"$name=${java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)}")
      .mkString("&")
    val response = java
      .net
      .http
      .HttpClient
      .newHttpClient()
      .send(
        java
          .net
          .http
          .HttpRequest
          .newBuilder(java.net.URI(page.postUrl))
          .header("Content-Type", "application/x-www-form-urlencoded")
          .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
          .build(),
        java.net.http.HttpResponse.BodyHandlers.ofString()
      )
    if response.statusCode != 200 then
      throw AssertionError(
        s"The UniFi page's post for $slug answered ${response.statusCode}: ${response.body}")
    Backoffice.tool[UnifiTools.SetEnabledRequest, UnifiTools.StatusResponse](
      "set_unifi_enabled",
      UnifiTools.SetEnabledRequest(slug, enabled = true))
  end configureUnifi

  /** The email, profiling and location surveys belong to the whole deployment, one active each:
    * created only when missing, with one question, for a guest to be identified at all.
    */
  private def ensureIdentification(): Unit =
    val present =
      Backoffice
        .tool[SurveyTools.ListRequest, SurveyTools.ListResponse](
          "list_surveys",
          SurveyTools.ListRequest())
        .surveys
        .filter(survey => survey.locationSlug.isEmpty && survey.questions.nonEmpty)
        .map(_.category)
        .toSet
    IdentificationCategory
      .values
      .filterNot(category => present.contains(category.stored))
      .foreach: category =>
        val survey = Backoffice.tool[
          SurveyTools.CreateIdentificationSurveyRequest,
          SurveyTools.CreateSurveyResponse](
          "create_identification_survey",
          SurveyTools.CreateIdentificationSurveyRequest(category, Some(s"E2E ${category.stored}")))
        Backoffice.tool[SurveyTools.CreateQuestionRequest, SurveyTools.CreateQuestionResponse](
          "create_identification_question",
          SurveyTools.CreateQuestionRequest(
            surveyId = survey.surveyId,
            text = texts(s"Your ${category.stored}?"),
            answer = AnswerFormat.Input(None),
            description = None,
            placeholder = None,
            required = Some(true),
            points = None,
            hierarchyLevel = None
          )
        )
  end ensureIdentification

  /** What the upload page does with the URL a tool hands it: the file, with its own type. */
  private def upload(url: String): Unit =
    val connection = java
      .net
      .URI(url)
      .toURL
      .openConnection()
      .asInstanceOf[java.net.HttpURLConnection]
    connection.setRequestMethod("PUT")
    connection.setDoOutput(true)
    connection.setRequestProperty("Content-Type", "video/webm")
    val out = connection.getOutputStream
    try out.write(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(Run.setting("E2E_SPOT"))))
    finally out.close()
    val status = connection.getResponseCode
    if status / 100 != 2 then
      throw AssertionError(s"Uploading the spot to $url answered $status")
end Locations
