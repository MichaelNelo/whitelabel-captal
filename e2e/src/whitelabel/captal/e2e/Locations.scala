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

  /** `name`, with an advertiser video and the survey asked after it, deployed: the one shape most
    * features need, built once per run.
    */
  def withAdvertiserVideo(name: String): Deployed = deployed.getOrElseUpdate(
    name, {
      val location = create(name)
      addAdvertiserVideo(location)
      deploy(location)
    })

  /** Once per run for each `key`: `build` runs the first time a feature asks, and every scenario
    * after it shares what it built. For a feature that needs a location of its own shape.
    */
  def once(key: String)(build: => Deployed): Deployed = deployed.getOrElseUpdate(key, build)

  /** A new location, not yet deployed: created, its UniFi settings pointed at the stand-in with
    * access lasting `accessMinutes`, the deployment's identification surveys ensured, and every
    * text a guest sees written as `text <key>` in [[Locales]].
    */
  def create(name: String, accessMinutes: Int = Venue.AccessMinutes): Deployed =
    val run = scala.util.Random.alphanumeric.filter(_.isLetterOrDigit).take(4).mkString.toLowerCase
    val location = Deployed(s"e2e-$name-$run", s"E2E $name")
    Backoffice.tool[LocationTools.CreateRequest, LocationTools.CreateResponse](
      "create_location",
      LocationTools.CreateRequest(location.slug, location.name))
    configureUnifi(location.slug, accessMinutes)
    ensureIdentification()
    translate(location, key => text(key))
    location

  /** Writes every key the portal needs, in every locale of [[Locales]], as `text(key)`. */
  def translate(location: Deployed, text: String => String): Unit = Locales.foreach: locale =>
    Backoffice.tool[I18nTools.SetTranslationsRequest, I18nTools.SetTranslationsResponse](
      "set_translations",
      I18nTools.SetTranslationsRequest(
        slug = location.slug,
        locale = locale,
        entries = I18nSchema.keys[I18n].map(key => I18nTools.Translation(key, text(key))))
    )

  /** An advertiser of its own with one video, the spot, and the survey asked after it: one required
    * free-text question. Answers the video's id.
    */
  def addAdvertiserVideo(
      location: Deployed,
      title: String = "E2E spot",
      question: String = "Did you like it?"): String =
    val advertiser =
      Backoffice
        .tool[AdvertiserTools.CreateRequest, AdvertiserTools.CreateResponse](
          "create_advertiser",
          AdvertiserTools.CreateRequest(s"E2E advertiser ${location.slug} $title", 10))
        .id
    val video = Backoffice.tool[VideoTools.CreateVideoRequest, VideoTools.CreateVideoResponse](
      "create_advertiser_video",
      VideoTools.CreateVideoRequest(
        slug = location.slug,
        advertiserId = advertiser,
        title = texts(title),
        description = None,
        minWatchSeconds = None,
        priority = Some(1),
        questions = List(
          VideoTools.VideoQuestion(
            text = texts(question),
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
    video.videoId
  end addAdvertiserVideo

  /** Deploys it, follows the run to the end, and waits for its portal to answer. */
  def deploy(location: Deployed): Deployed =
    Backoffice.awaitRun(
      Backoffice.tool[DeployTools.DeployRequest, DeployTools.RunView](
        "deploy_location",
        DeployTools.DeployRequest(location.slug)))
    Portal.awaitServing(location)
    location

  def texts(value: String): List[LocalizedText] = Locales.map(LocalizedText(_, value))

  /** What the page `configure_unifi` opens does: posts the controller's settings with the ticket
    * the tool handed it. Then the calls are turned on, which a new location has by default anyway.
    * The controller is the stand-in unless `host` and `port` name another, such as one that is not
    * there.
    */
  def configureUnifi(
      slug: String,
      accessMinutes: Int,
      host: String = Run.setting("E2E_UNIFI_HOST"),
      port: String = Run.setting("E2E_UNIFI_PORT")): Unit =
    val page = Backoffice.tool[UnifiTools.ConfigureRequest, UnifiTools.ConfigureResponse](
      "configure_unifi",
      UnifiTools.ConfigureRequest(slug))
    val form = Map(
      "ticket"          -> page.ticket,
      "host"            -> host,
      "port"            -> port,
      "siteId"          -> Venue.Site,
      "apiToken"        -> Venue.ApiKey,
      "durationMinutes" -> accessMinutes.toString)
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

  /** What the upload page does with the URL a tool hands it: the spot, with its own type. */
  def upload(url: String): Unit =
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
