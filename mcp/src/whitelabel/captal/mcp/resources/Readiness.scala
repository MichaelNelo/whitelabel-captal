package whitelabel.captal.mcp.resources

import com.jamesward.ziohttp.mcp.*
import io.getquill.*
import whitelabel.captal.core.i18n.{I18n, I18nSchema}
import whitelabel.captal.core.video.VideoType
import whitelabel.captal.mcp.aws.{Asset, AssetStore}
import whitelabel.captal.mcp.protocol.{McpSchema, ToolSchema}
import whitelabel.captal.mcp.tools.{IdentificationCategory, SurveyCategory}
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.core.given
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.{
  AdvertiserRow,
  AdvertiserVideoRow,
  LocalizedTextRow,
  LocationRow,
  QuestionRow,
  SurveyCategory as StoredCategory,
  SurveyRow,
  UnifiAccess,
  VideoStatus
}
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
  * from this one and the two would disagree in front of the operator.
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
        "True only when all three of host, siteId and apiToken are stored — which is what UnifiAccess needs to build. Any one missing and the portal completes the whole flow and then authorizes nobody: the handler logs and skips, and the guest is left at Ready. Call get_unifi_config for which field is missing, configure_unifi to set it.")
      unifiConfigured: Boolean,
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
        "What is left to do, in the order worth doing it. Empty means nothing here blocks a deploy.")
      nextSteps: List[String])

  final case class ReadinessRequest(
      @description("Which location. Call list_locations for the slugs.")
      slug: String)

  given Schema[ReadinessRequest] = DeriveSchema.gen
  given Schema[LocaleGap] = DeriveSchema.gen
  given Schema[LocationReadiness] = DeriveSchema.gen

  private val requiredKeys: List[String] = summon[I18nSchema[I18n]].keys("")

  private inline def textsForLocation = quote: (locationIdParam: String) =>
    query[LocalizedTextRow].filter(t =>
      t.locationId.contains(locationIdParam) && t.category == "frontend" && t.isActive == 1)

  /** Reported rather than enforced. A location with no video is a perfectly good location to keep
    * translating; the point is that the operator is told, not stopped.
    */
  private[mcp] def steps(r: LocationReadiness): List[String] =
    val translations =
      if r.locales.isEmpty then
        List(
          "No translations at all. Call set_translations — until then the portal shows guests the raw key names.")
      else
        r.locales
          .filter(_.missing > 0)
          .map(g =>
            s"Locale '${g.locale}' is missing ${g.missing} of ${r
                .requiredKeys} required keys. Call get_i18n_status for which, then set_translations.")
    val advertisers =
      if r.advertisers == 0 then
        List("No advertisers exist. Call create_advertiser before adding a video.")
      else
        Nil
    val videos =
      if r.activeVideos == 0 && r.pendingVideos == 0 then
        List("No videos. Call create_advertiser_video, which creates the survey that follows it.")
      else if r.pendingVideos > 0 then
        List(
          s"${r.pendingVideos} video(s) are waiting for their file. Upload to the signed URL, then call confirm_video_upload.")
      else
        Nil
    // Separate from the count of advertisers: there can be plenty active and none of them the owner
    // of a video here.
    val stranded =
      if r.strandedAdVideos > 0 then
        List(
          s"${r.strandedAdVideos} advertiser video(s) in rotation here belong to a retired advertiser, so the selection never draws them. Call list_advertisers, then set_advertiser_active to bring the advertiser back, or set_video_active to retire the videos.")
      else
        Nil
    val surveys =
      if r.adVideos > r.advertiserSurveys then
        List(
          s"${r.adVideos -
              r.advertiserSurveys} advertiser video(s) have no survey with an active question, and an advertiser's video is only drawn while its survey has something left to ask — so those are never served. Call get_survey for the video's survey, then create_question. Promos are not counted here: they play with or without one.")
      else
        Nil
    val branding =
      List(
        Option.when(!r.hasStyles)(
          "No stylesheet of its own; the portal uses the bundle's. Call set_location_styles if it should be branded."),
        Option.when(!r.hasIcon)(
          "No brand icon of its own. Call set_location_icon if it should be branded.")
      ).flatten
    val unifi =
      if r.unifiConfigured then
        Nil
      else
        List(
          "UniFi is not fully configured — host, siteId and apiToken are all three required — so the portal completes the flow and then authorizes nobody, leaving every guest at Ready. Call get_unifi_config to see which field is missing and configure_unifi to set it.")
    // First, because it blocks everything after it. Without the email survey a guest never gets
    // identified, so no video is ever drawn and no advertiser question is ever asked — a location
    // can be complete in every other respect and still show nobody anything.
    val identification =
      if r.missingIdentificationSurveys.isEmpty then
        Nil
      else
        List(
          s"The ${r
              .missingIdentificationSurveys
              .mkString(
                " and ")} survey(s) do not exist, so a guest is never identified and nothing past the first screen is reached. Call create_identification_survey. They are deployment-wide: this fixes every location at once, and needs identity:write.")
    identification ++ translations ++ advertisers ++ videos ++ stranded ++ surveys ++ branding ++
      unifi
  end steps

  def of(slug: String): ZIO[QuillSqlite & AssetStore, String, LocationReadiness] = ZIO
    .serviceWithZIO[QuillSqlite]: quill =>
      import quill.*
      for
        locations <- run(query[LocationRow].filter(_.slug == lift(slug))).orDie
        row       <- ZIO
          .succeed(locations.headOption)
          .someOrFail(s"No location with slug '$slug'. Call list_locations to see what exists.")
        texts       <- run(textsForLocation(lift(row.id))).orDie
        videos      <- run(query[AdvertiserVideoRow]).orDie
        surveys     <- run(query[SurveyRow]).orDie
        advertisers <- run(query[AdvertiserRow]).orDie
        questions   <- run(query[QuestionRow]).orDie
        store       <- ZIO.service[AssetStore]
        styles      <- store.exists(store.key(slug, Asset.Styles))
        icon        <- store.exists(store.key(slug, Asset.BrandIcon))
        mine                = videos.filter(_.locationId.contains(row.id))
        inRotation          = mine.filter(_.status == VideoStatus.Active)
        activeAdvertiserIds = advertisers.filter(_.isActive == 1).map(_.id).toSet
        scoped              = surveys.filter(s => s.locationId.contains(row.id) && s.isActive == 1)
        // Counted per locale against the derived key list rather than against whatever is stored,
        // which is the whole point of deriving them: a key nobody remembered to add is missing, not
        // absent from the comparison.
        byLocale = texts
          .groupBy(_.locale)
          .toList
          .sortBy(_._1)
          .map: (locale, rows) =>
            val present = rows.map(_.entityId).toSet
            LocaleGap(locale, requiredKeys.count(!present.contains(_)))
        bare = LocationReadiness(
          slug = row.slug,
          name = row.name,
          active = row.isActive == 1,
          // All three, not just the host. `UnifiAccess.fromRow` is a for-comprehension over
          // `unifiHost`, `unifiApiToken` and `unifiSiteId`, and answers `None` if any is absent —
          // at which point the handler logs and leaves the session at Ready. Reporting on the host
          // alone made this field say "configured" about a location that authorizes nobody, which
          // is the exact failure the whole tool exists to surface.
          unifiConfigured = UnifiAccess.fromRow(row).isDefined,
          locales = byLocale,
          requiredKeys = requiredKeys.size,
          hasStyles = styles,
          hasIcon = icon,
          activeVideos = mine.count(_.status == VideoStatus.Active),
          pendingVideos = mine.count(_.status == VideoStatus.Pending),
          advertiserSurveys = scoped.count(s =>
            s.category == StoredCategory.Advertiser &&
              questions.exists(q => q.surveyId == s.id && q.isActive == 1)),
          adVideos = inRotation.count(_.videoType == VideoType.Ad),
          // The join the ad selection does, counted here: `nextAdQueryAuthenticated` requires the
          // advertiser to be active, so a video whose advertiser was retired is drawn by nothing.
          // `advertisers` below cannot show this — it counts active advertisers across the whole
          // deployment, so a location full of stranded videos reads as healthy beside it.
          strandedAdVideos = inRotation.count(v =>
            v.videoType == VideoType.Ad && !v.advertiserId.exists(activeAdvertiserIds.contains)),
          advertisers = advertisers.count(_.isActive == 1),
          // Deployment-wide, so deliberately not filtered by location: the question is whether the
          // three exist at all, and an active one anywhere serves every portal.
          // Usable, not merely present. `firstEmailSurveyQuery` and
          // `nextIdentificationSurveyQuery` both join `questions` and filter on active, so a
          // survey whose questions were all retired returns no rows and the guest is never
          // identified — `SurveyRepositoryQuill` even logs that its "questions may have been
          // retired". This checked only that the row existed, while the advertiser count two lines
          // up already checked for a live question. The asymmetry was an oversight.
          missingIdentificationSurveys = IdentificationCategory
            .values
            .toList
            .map(_.stored)
            .filterNot(category =>
              surveys.exists(s =>
                s.category == category && s.isActive == 1 &&
                  questions.exists(q => q.surveyId == s.id && q.isActive == 1))),
          nextSteps = Nil
        )
      yield bare.copy(nextSteps = steps(bare))
      end for

  val tool: McpToolHandlerR[QuillSqlite & AssetStore] = McpTool("get_location_readiness")
    .description(
      "What a location still needs before it is worth deploying: translation gaps per locale, branding, videos, and the advertisers available to draw from. Ends with the next steps in the order worth doing them. Read it between steps rather than tracking the state yourself — every tool writes immediately, so there is no commit step and stopping part way leaves nothing broken.")
    .readOnly
    .handle: (in: ReadinessRequest) =>
      of(in.slug.trim)

  val all: List[McpToolHandlerR[QuillSqlite & AssetStore]] = List(tool)
end Readiness
