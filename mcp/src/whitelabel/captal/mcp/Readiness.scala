package whitelabel.captal.mcp

import io.getquill.*
import whitelabel.captal.core.i18n.{I18n, I18nSchema}
import whitelabel.captal.infra.schema.QuillSqlite
import whitelabel.captal.infra.schema.core.given
import whitelabel.captal.infra.schema.given
import whitelabel.captal.infra.{
  AdvertiserRow,
  AdvertiserVideoRow,
  LocalizedTextRow,
  LocationRow,
  QuestionRow,
  SurveyRow
}
import zio.*
import zio.schema.annotation.description
import zio.schema.{DeriveSchema, Schema}

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
  * Shared deliberately between the `captal://locations/{slug}` resource and the `onboard-location`
  * prompt: a second implementation of "what is missing" would drift from this one and the two would
  * disagree in front of the operator.
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
        "False means no UniFi host is set. Everything else can be finished and the portal still cannot authorize a guest. Configured outside this server.")
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
        "Advertiser surveys scoped to this location. One per video, created with it — a count below activeVideos means a video is in rotation with nothing to ask afterwards.")
      advertiserSurveys: Int,
      @description(
        "Advertisers active anywhere. They are not scoped to a location, so this is a deployment-wide count; zero means no video can be attributed to anyone.")
      advertisers: Int,
      @description(
        "What is left to do, in the order worth doing it. Empty means nothing here blocks a deploy.")
      nextSteps: List[String])

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
          "UniFi is not configured, so the portal cannot authorize guests here. That is set outside this server.")
    translations ++ advertisers ++ videos ++ branding ++ unifi
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
        mine   = videos.filter(_.locationId.contains(row.id))
        scoped = surveys.filter(s => s.locationId.contains(row.id) && s.isActive == 1)
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
          unifiConfigured = row.unifiHost.exists(_.trim.nonEmpty),
          locales = byLocale,
          requiredKeys = requiredKeys.size,
          hasStyles = styles,
          hasIcon = icon,
          activeVideos = mine.count(_.isActive == 1),
          pendingVideos = mine.count(_.isActive == 0),
          advertiserSurveys = scoped.count(s =>
            s.category == "advertiser" &&
              questions.exists(q => q.surveyId == s.id && q.isActive == 1)),
          advertisers = advertisers.count(_.isActive == 1),
          nextSteps = Nil
        )
      yield bare.copy(nextSteps = steps(bare))
      end for
end Readiness
