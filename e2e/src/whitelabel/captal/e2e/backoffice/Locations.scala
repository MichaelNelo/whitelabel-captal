package whitelabel.captal.e2e.backoffice

import scala.collection.concurrent.TrieMap

import com.microsoft.playwright.Locator
import whitelabel.captal.core.i18n.{I18n, I18nSchema}
import whitelabel.captal.e2e.guest.Portal
import whitelabel.captal.e2e.scenario.Run
import whitelabel.captal.e2e.venue.Venue
import whitelabel.captal.mcp.tools.{
  AdvertiserTools,
  AnswerFormat,
  DeployTools,
  I18nTools,
  IdentificationCategory,
  LocalizedText,
  LocationTools,
  RecoveryTools,
  SurveyTools,
  TermsTools,
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
    /** The portal as UniFi sends a guest to it, from the device `mac` behind the access point, with
      * the fingerprint the link shortener gave the device, when it gave one: what the load
      * balancer's dispatch rule for the location's access point redirects that request to. Fails
      * when it sends the phone anywhere but this location's portal.
      */
    def portal(mac: String, fingerprint: Option[String]): String =
      val back = java.net.URLEncoder.encode("http://example.com", "UTF-8")
      val query =
        s"id=$mac&ap=${Venue.apFor(slug)}&ssid=E2E&url=$back" +
          fingerprint.fold("")(sent => s"&fingerprint=$sent")
      val sent = Venue.dispatched(query)
      val (to, expected) = (java.net.URI.create(sent), java.net.URI.create(address))
      if to.getHost != expected.getHost || to.getPath != expected.getPath then
        throw AssertionError(
          s"The access point of $slug sent its guest to $sent rather than to its portal, $address")
      sent

    /** The portal's address alone, as a phone opens it outside the venue's WiFi: no redirect. */
    def address: String = s"${Run.setting("E2E_PORTAL")}/$slug/"

    def api(path: String): String = s"${Run.setting("E2E_PORTAL")}/$slug/api/$path"
  end Deployed

  /** Every text a guest sees, in both locales, as `text <key>`: a step finds a button by its key.
    */
  val Locales = List("en", "es")

  def text(key: String): String = s"text $key"

  private val deployed = TrieMap.empty[String, Deployed]

  /** `name`, with an advertiser video and the survey asked after it, deployed: the one shape most
    * features need, built once per feature ([[release]]).
    */
  def withAdvertiserVideo(name: String): Deployed = deployed.getOrElseUpdate(
    name, {
      val location = create(name)
      addAdvertiserVideo(location)
      deploy(location)
    })

  /** Once per feature for each `key`: `build` runs the first time a scenario of it asks, and every
    * scenario after it in the feature shares what it built ([[release]]). For a feature that needs
    * a location of its own shape.
    */
  def once(key: String)(build: => Deployed): Deployed = deployed.getOrElseUpdate(key, build)

  /** What a slug leaves for a location's name: 25, less `e2e-` and `-<4 characters>`. */
  val SlugName = 16

  /** A new location, not yet deployed: created, configured with an access point of its own that
    * answers to the stand-in — or to the controller at `host` — with access lasting
    * `accessMinutes`, the deployment's identification surveys ensured, and every text a guest sees
    * written as `text <key>` in [[Locales]].
    *
    * Its slug is `e2e-<name>-<4 characters>`, with `name` cut to [[SlugName]] characters: the
    * backoffice takes 25 at most, since the slug names AWS resources with limits of their own. The
    * display name keeps all of `name`.
    */
  def create(
      name: String,
      accessMinutes: Int = Venue.AccessMinutes,
      host: String = Run.setting("E2E_UNIFI_HOST")): Deployed =
    val run = scala.util.Random.alphanumeric.filter(_.isLetterOrDigit).take(4).mkString.toLowerCase
    val short = name.take(SlugName).stripSuffix("-")
    val location = Deployed(s"e2e-$short-$run", s"E2E $name")
    Backoffice.tool[LocationTools.CreateRequest, LocationTools.CreateResponse](
      "create_location",
      LocationTools.CreateRequest(location.slug, location.name))
    configureUnifi(location.slug, accessMinutes, host = host)
    ensureIdentification()
    ensureTerms()
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
    * free-text question. The spot goes up through the page the tool opens. Answers the video's id.
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
    val (video, _) = Operator.upload[VideoTools.CreateVideoRequest, VideoTools.CreateVideoResponse](
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
    video.videoId
  end addAdvertiserVideo

  /** A promo of the venue's own, the spot, at `priority` in its rotation, with the survey asked
    * after it when there is a `question`. Up through the page the tool opens. Answers its id.
    */
  def addPromo(
      location: Deployed,
      title: String,
      priority: Int,
      question: Option[String] = None): String =
    val (promo, _) = Operator.upload[VideoTools.CreatePromoRequest, VideoTools.CreatePromoResponse](
      "create_promo_video",
      VideoTools.CreatePromoRequest(
        slug = location.slug,
        title = texts(title),
        description = None,
        minWatchSeconds = None,
        priority = Some(priority),
        noRepeatSeconds = None,
        questions = question.map(text =>
          List(
            VideoTools.VideoQuestion(
              text = texts(text),
              answer = AnswerFormat.Input(None),
              required = Some(true),
              points = Some(1))))
      )
    )
    promo.videoId
  end addPromo

  /** Deploys it, watching the run on the page the tool opens, and waits for its portal to answer.
    */
  def deploy(location: Deployed): Deployed =
    Operator.run("deploy_location", DeployTools.DeployRequest(location.slug))
    Portal.awaitServing(location)
    location

  def texts(value: String): List[LocalizedText] = Locales.map(LocalizedText(_, value))

  /** The location configured with the access point `ap` — its own ([[Venue.apFor]]) unless another
    * is named — and its access duration, entered on the page `configure_unifi` opens and saved by
    * it. Then the calls are turned on, which a new location has by default anyway.
    *
    * An access point the system has answers to its own controller, which the page picks. A new one
    * answers to the stand-in unless `host` and `port` name another, such as one that is not there:
    * chosen from the page's list when the system has it, and entered with its token when it does
    * not, so the first location a run configures takes the page's one path and every later one the
    * other. Read by a location's API as it authorizes a guest, so settings entered after a deploy
    * apply to the next one.
    */
  def configureUnifi(
      slug: String,
      accessMinutes: Int,
      host: String = Run.setting("E2E_UNIFI_HOST"),
      port: String = Run.setting("E2E_UNIFI_PORT"),
      ap: Option[String] = None): Unit =
    val mac = ap.getOrElse(Venue.apFor(slug))
    val (config, opened) = Operator.open[UnifiTools.ConfigureRequest, UnifiTools.ConfigureResponse](
      "configure_unifi",
      UnifiTools.ConfigureRequest(slug))
    val form = opened.app
    form.locator("#form.ready").waitFor(Locator.WaitForOptions().setTimeout(60_000))
    opened.linger()
    form.locator("#apMac").fill(mac)
    if !config.knownAccessPoints.exists(_.mac == mac) then
      config
        .controllers
        .find(controller =>
          controller.host == host && controller.siteId == Venue.Site &&
            controller.port.getOrElse(443) == port.toIntOption.getOrElse(443)) match
        case Some(controller) =>
          form.locator("#controllerId").selectOption(controller.id)
        case None =>
          form.locator("#controllerId").selectOption("new")
          form.locator("#host").fill(host)
          form.locator("#port").fill(port)
          form.locator("#siteId").fill(Venue.Site)
          form.locator("#apiToken").fill(Venue.ApiKey)
    form.locator("#durationMinutes").fill(accessMinutes.toString)
    opened.linger()
    form.locator("#save").click()
    // Saved, the page tells the model so; refused, it says why on the page and nothing else.
    if !opened.awaitUntil(60)(opened.toldTheModel.nonEmpty) then
      throw AssertionError(
        s"The UniFi page of $slug saved nothing: ${form.locator("#status").textContent()}")
    opened.linger()
    Backoffice.tool[UnifiTools.UpdateAccessPointRequest, UnifiTools.StatusResponse](
      "update_access_point",
      UnifiTools.UpdateAccessPointRequest(slug, mac, unifiEnabled = Some(true)))
  end configureUnifi

  /** Changes how a location treats errors and abandoned playbacks, as an operator does. Its portal
    * and the backoffice read it as they need it, so it needs no deploy.
    */
  def setRecovery(change: RecoveryTools.SetRequest): RecoveryTools.PolicyView = Backoffice.tool[
    RecoveryTools.SetRequest,
    RecoveryTools.PolicyView]("set_recovery_policy", change)

  /** The email, profiling and location surveys belong to the whole deployment, one active each:
    * created only when missing, with one question, for a guest to be identified at all.
    */
  /** Destroys every location these features made — this run's, and any a run that was cut short
    * left standing: every `e2e-` slug whose latest run is not a destroy that succeeded. Every
    * teardown is started first and each then followed to its end, so they run side by side. One
    * that fails is reported and left: the run's outcome is its scenarios'. The objects in S3 go
    * with it, which [[Backoffice]] answers yes to; the rows stay.
    */
  def sweep(): Unit = destroy(
    "the features made",
    Backoffice
      .tool[LocationTools.ListRequest, LocationTools.ListResponse](
        "list_locations",
        LocationTools.ListRequest(includeInactive = true))
      .locations
      .map(_.slug)
      .filter(_.startsWith("e2e-"))
  )

  /** Destroys every location this run has built so far, and forgets them, so a feature that asks
    * for one again builds it anew. Called as each feature after the first begins: every location in
    * a run is a task in LocalStack, an API's JVM of its own, and a run that kept them all until the
    * end held one for every scenario at once.
    */
  def release(): Unit =
    val built = deployed.values.map(_.slug).toList.distinct
    deployed.clear()
    destroy("the previous feature made", built)

  /** The `slugs` still standing — each whose latest run is not a destroy that succeeded, and not
    * one never deployed — destroyed side by side: every teardown started, then each followed to its
    * end.
    */
  private def destroy(made: String, slugs: List[String]): Unit =
    val standing = slugs.filter(slug =>
      latestRun(slug).exists(run => !(run.operation == "destroy" && run.status == "succeeded")))
    if standing.nonEmpty then
      println(s"[e2e] Destroying the locations $made: ${standing.mkString(", ")}")
    val started = standing.flatMap(slug =>
      attempt(s"destroy $slug")(
        Backoffice.tool[DeployTools.DestroyRequest, DeployTools.RunView](
          "destroy_location",
          DeployTools.DestroyRequest(slug = slug, confirm = slug))))
    started.foreach(run => attempt(s"destroy ${run.slug}")(Backoffice.awaitRun(run)))
  end destroy

  /** A location's latest run, or none when it never had one: then nothing of it was deployed. */
  private def latestRun(slug: String): Option[DeployTools.RunView] =
    scala
      .util
      .Try(
        Backoffice.tool[DeployTools.StatusRequest, DeployTools.RunView](
          "get_deploy_status",
          DeployTools.StatusRequest(runId = None, slug = Some(slug))))
      .toOption

  private def attempt[A](what: String)(work: => A): Option[A] = scala
    .util
    .Try(work)
    .fold(
      failure =>
        println(s"[e2e] Could not $what: ${failure.getMessage}")
        None
      ,
      Some(_))

  /** The terms of service in force, read as the operator would. */
  def terms(): TermsTools.TermsView = Backoffice.tool[TermsTools.GetRequest, TermsTools.TermsView](
    "get_terms_of_service",
    TermsTools.GetRequest())

  /** Publishes a new version of the terms, in every locale the one in force has and the features'
    * own, which every device is asked to accept again.
    */
  def publishTerms(): TermsTools.TermsView =
    val current = terms().current
    val locales = (Locales ++ current.toList.flatMap(_.translations.map(_.locale))).distinct
    val next = current.fold(1)(_.version + 1)
    Backoffice.tool[TermsTools.CreateRequest, TermsTools.TermsView](
      "create_terms_version",
      TermsTools.CreateRequest(
        locales.map(locale => TermsTools.Translation(locale, s"E2E terms v$next ($locale)"))))

  /** Terms in force in every locale of [[Locales]]: a location is not deployed without them, and
    * the portal offers only their locales. Deployment-wide, so written once and kept.
    */
  private def ensureTerms(): Unit =
    terms().current match
      case None =>
        val _ = publishTerms()
      case Some(version) =>
        Locales
          .filterNot(locale => version.translations.exists(_.locale == locale))
          .foreach: locale =>
            Backoffice.tool[TermsTools.SetTranslationRequest, TermsTools.TermsView](
              "set_terms_translation",
              TermsTools.SetTranslationRequest(locale, s"E2E terms v${version.version} ($locale)"))

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

end Locations
