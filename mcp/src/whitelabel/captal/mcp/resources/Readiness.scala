package whitelabel.captal.mcp.resources

import cats.syntax.foldable.*
import com.jamesward.ziohttp.mcp.*
import whitelabel.captal.core.application.Error as AppError
import whitelabel.captal.core.application.queries.{
  LocationReadinessQuery,
  ReadinessReport,
  ReadinessStep
}
import whitelabel.captal.mcp.protocol.{McpSchema, ToolSchema}
import whitelabel.captal.mcp.tools.LocationCommands
import whitelabel.captal.platform.aws.AssetStore
import whitelabel.captal.platform.queries.LocationReadiness as ReadinessQuery
import whitelabel.captal.platform.schema.QuillSqlite
import zio.*
import zio.schema.annotation.description
import zio.schema.{DeriveSchema, Schema}

import McpSchema.given
import ToolSchema.readOnly

/** What a location is still missing before it is worth deploying.
  *
  * Onboarding a location is a sequence — create it, give it translations, register advertisers, add
  * videos with the questions that follow them — and nothing in MCP models a sequence. Tools are
  * individual capabilities and the agent is the orchestrator.
  *
  * So rather than guard against a half-finished location, this makes leaving one half-finished
  * cheap: the state is readable at any point and says what to do next. Partial is not invalid — a
  * survey with one question serves exactly as well as one with two — and a location no service
  * points at is not read by anything, so there is nothing to protect against. What there is to
  * avoid is an operator who cannot tell where they stopped.
  *
  * Shared deliberately between the `captal://locations/{slug}` resource, the `onboard-location`
  * prompt and `get_location_readiness`: a second implementation of "what is missing" would drift
  * from this one and the two would disagree in front of the operator. What is read and which steps
  * it leaves are `core`'s query and rule ([[LocationReadinessQuery]]); this words each step, naming
  * the tool that takes it.
  *
  * The tool was the last of the three to exist, and its absence was the gap that mattered. A
  * resource is not something every host lets a model read, and a prompt is invoked by the operator
  * and never by the model — so the instruction to "re-run it as you go" named the one thing a model
  * cannot do, in a server whose whole onboarding story is re-reading this between steps.
  */
object Readiness:

  final case class LocaleGap(
      @description("BCP 47 tag, as stored.")
      locale: String,
      @description("Required keys with no value in this locale.")
      missing: Int)

  final case class LocationReadiness(
      @description("The location this describes.")
      slug: String,
      @description("Display name.")
      name: String,
      @description("A retired location serves nothing, whatever else is complete.")
      active: Boolean,
      @description(
        "True when the location has at least one access point, each registered with its controller's host, site id and token. Without one every guest is turned away with an error as they arrive: the AP a guest comes through is what says which controller grants them access. Call get_unifi_config to list them, configure_unifi to register one.")
      unifiConfigured: Boolean,
      @description(
        "Whether any of its access points has its UniFi calls on. False with access points means the portal never calls a controller here: guests finish the flow and stay at Ready. Turned off or on per access point with update_access_point.")
      unifiEnabled: Boolean,
      @description(
        "Locales with at least one translation, and how many required keys each still lacks. A locale absent here has none at all; an empty list means the portal would render raw key names to every guest.")
      locales: List[LocaleGap],
      @description("How many keys the portal requires, derived from the I18n type.")
      requiredKeys: Int,
      @description("Whether this location has a stylesheet of its own rather than the default.")
      hasStyles: Boolean,
      @description("Whether this location has a brand icon of its own rather than the default.")
      hasIcon: Boolean,
      @description(
        "Videos in rotation here. Zero means a guest gets past identification and is shown nothing.")
      activeVideos: Int,
      @description("Videos created but still waiting for their file, so out of rotation.")
      pendingVideos: Int,
      @description(
        "Video surveys scoped to this location that still have an active question. Compare against adVideos, not activeVideos: an advertiser's video is only ever drawn while its survey has something left to ask, so a count below adVideos means a video that can never be served. A promo is drawn either way and may legitimately have none.")
      advertiserSurveys: Int,
      @description(
        "Of the videos in rotation here, how many are an advertiser's. The rest are promos, which belong to the venue and need neither an advertiser nor a survey.")
      adVideos: Int,
      @description(
        "Advertiser videos in rotation here whose advertiser is retired or missing. The selection joins advertisers and filters on active, so these are drawn by nothing — the location looks stocked and serves only promos. Bring the advertiser back with set_advertiser_active, or retire the videos with set_video_active.")
      strandedAdVideos: Int,
      @description(
        "Advertisers active anywhere. They are not scoped to a location, so this is a deployment-wide count; zero means no video can be attributed to anyone.")
      advertisers: Int,
      @description(
        "Which of the three identification surveys — email, profiling, location — are unusable: absent, retired, or active with no active question left. The last case matters as much as the first, because the query that serves them joins questions, so a survey whose questions were all retired returns nothing and the guest is never identified. Deployment-wide rather than this location's, but a guest here cannot be identified without the email one; creating or reviving it fixes every location at once.")
      missingIdentificationSurveys: List[String],
      @description(
        "The version of the terms of service in force, the same for every location. Absent before the first is created, and until then deploy_location refuses: a portal asks every guest to accept them before the flow begins.")
      termsVersion: Option[Int],
      @description(
        "Locales this location has translations in that the terms in force are not written in. A portal offers only the terms' locales, so a guest cannot pick these.")
      localesWithoutTerms: List[String],
      @description(
        "What is left to do, in the order worth doing it. Empty means nothing here blocks a deploy.")
      nextSteps: List[String])

  final case class ReadinessRequest(
      @description("Which location. Call list_locations for the slugs.")
      slug: String)

  given Schema[ReadinessRequest] = DeriveSchema.gen
  given Schema[LocaleGap] = DeriveSchema.gen
  given Schema[LocationReadiness] = DeriveSchema.gen

  /** What the operator reads for each step, naming the tool that takes it. */
  private[mcp] def render(step: ReadinessStep): String =
    step match
      case ReadinessStep.IdentificationSurveysMissing(categories) =>
        s"The ${categories.mkString(
            " and ")} survey(s) do not exist, so a guest is never identified and nothing past the first screen is reached. Call create_identification_survey. They are deployment-wide: this fixes every location at once, and needs identity:write."
      case ReadinessStep.NoTerms =>
        "No terms of service exist, so deploy_location refuses: every portal asks its guests to accept them before the flow begins. Call create_terms_version with a text in each of this location's locales. They are the same for every location."
      case ReadinessStep.TermsWithoutLocales(locales) =>
        s"The terms of service in force have no text in ${locales.mkString(
            ", ")}, so the portal does not offer ${
            if locales.sizeIs == 1 then
              "that locale"
            else
              "those locales"
          }. Add the text with set_terms_translation."
      case ReadinessStep.NoTranslations =>
        "No translations at all. Call set_translations — until then the portal shows guests the raw key names."
      case ReadinessStep.LocaleIncomplete(locale, missing, of) =>
        s"Locale '$locale' is missing $missing of $of required keys. Call get_i18n_status for which, then set_translations."
      case ReadinessStep.NoAdvertisers =>
        "No advertisers exist. Call create_advertiser before adding a video."
      case ReadinessStep.NoVideos =>
        "No videos. Call create_advertiser_video, which creates the survey that follows it."
      case ReadinessStep.VideosAwaitingFile(count) =>
        s"$count video(s) are waiting for their file. Upload to the signed URL, then call confirm_video_upload."
      case ReadinessStep.AdVideosStranded(count) =>
        s"$count advertiser video(s) in rotation here belong to a retired advertiser, so the selection never draws them. Call list_advertisers, then set_advertiser_active to bring the advertiser back, or set_video_active to retire the videos."
      case ReadinessStep.AdVideosWithoutSurvey(count) =>
        s"$count advertiser video(s) have no survey with an active question, and an advertiser's video is only drawn while its survey has something left to ask — so those are never served. Call get_survey for the video's survey, then create_question. Promos are not counted here: they play with or without one."
      case ReadinessStep.NoStyles =>
        "No stylesheet of its own; the portal uses the bundle's. Call set_location_styles if it should be branded."
      case ReadinessStep.NoIcon =>
        "No brand icon of its own. Call set_location_icon if it should be branded."
      case ReadinessStep.NoAccessPoint =>
        "The location has no access point, so every guest is turned away with an error as they arrive. Call configure_unifi to register one with its controller's host, site id and token."

  private def fromReport(r: ReadinessReport): LocationReadiness = LocationReadiness(
    slug = r.slug,
    name = r.name,
    active = r.active,
    unifiConfigured = r.unifiConfigured,
    unifiEnabled = r.unifiEnabled,
    locales = r.locales.map(gap => LocaleGap(gap.locale, gap.missing)),
    requiredKeys = r.requiredKeys,
    hasStyles = r.hasStyles,
    hasIcon = r.hasIcon,
    activeVideos = r.activeVideos,
    pendingVideos = r.pendingVideos,
    advertiserSurveys = r.advertiserSurveys,
    adVideos = r.adVideos,
    strandedAdVideos = r.strandedAdVideos,
    advertisers = r.advertisers,
    missingIdentificationSurveys = r.missingIdentificationSurveys,
    termsVersion = r.termsVersion,
    localesWithoutTerms = r.localesWithoutTerms,
    nextSteps = r.nextSteps.map(render)
  )

  private def refusal(error: AppError): String =
    error match
      case AppError.Location(found) =>
        found.toList.map(LocationCommands.describe).mkString(" ")
      case other =>
        other.toString

  def of(slug: String): ZIO[QuillSqlite & AssetStore, String, LocationReadiness] =
    for
      quill  <- ZIO.service[QuillSqlite]
      assets <- ZIO.service[AssetStore]
      found  <- ReadinessQuery(quill, assets)
        .execute(LocationReadinessQuery(slug.trim))
        .mapError(e => s"Could not read the location's readiness: ${e.getMessage}")
      report <- ZIO.fromEither(found).mapError(refusal)
    yield fromReport(report)

  val tool: McpToolHandlerR[QuillSqlite & AssetStore] = McpTool("get_location_readiness")
    .description(
      "What a location still needs before it is worth deploying: translation gaps per locale, branding, videos, and the advertisers available to draw from. Ends with the next steps in the order worth doing them. Read it between steps rather than tracking the state yourself — every tool writes immediately, so there is no commit step and stopping part way leaves nothing broken.")
    .readOnly
    .handle: (in: ReadinessRequest) =>
      of(in.slug)

  val all: List[McpToolHandlerR[QuillSqlite & AssetStore]] = List(tool)
end Readiness
