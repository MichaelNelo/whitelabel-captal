package whitelabel.captal.core.application.queries

/** What a location with this slug still needs before it is worth deploying. */
final case class LocationReadinessQuery(slug: String)

/** A locale with at least one translation, and how many required keys it still lacks. */
final case class LocaleGap(locale: String, missing: Int)

/** What readiness is read from, and the steps it leaves in the order worth doing them
  * ([[LocationReadinessQuery.nextSteps]]).
  *
  * The counts are what the portal's selections see: `adVideos` and `strandedAdVideos` are of the
  * videos in rotation, `advertiserSurveys` of the location's advertiser surveys that still have an
  * active question, `advertisers` and `missingIdentificationSurveys` deployment-wide, since neither
  * belongs to a location.
  */
final case class ReadinessReport(
    slug: String,
    name: String,
    active: Boolean,
    unifiConfigured: Boolean,
    unifiEnabled: Boolean,
    locales: List[LocaleGap],
    requiredKeys: Int,
    hasStyles: Boolean,
    hasIcon: Boolean,
    activeVideos: Int,
    pendingVideos: Int,
    advertiserSurveys: Int,
    adVideos: Int,
    strandedAdVideos: Int,
    advertisers: Int,
    missingIdentificationSurveys: List[String],
    termsVersion: Option[Int],
    localesWithoutTerms: List[String],
    nextSteps: List[ReadinessStep])

/** One thing left to do before a deploy is worth it. Data only: the backoffice words it, naming its
  * own tools.
  */
enum ReadinessStep:
  /** No guest is identified without them, so nothing past the first screen is reached. */
  case IdentificationSurveysMissing(categories: List[String])

  /** A deploy is refused until the first version exists. */
  case NoTerms

  /** The portal offers only the locales the terms in force are written in. */
  case TermsWithoutLocales(locales: List[String])

  /** The portal would show guests the raw key names. */
  case NoTranslations
  case LocaleIncomplete(locale: String, missing: Int, of: Int)

  /** A video needs one. */
  case NoAdvertisers
  case NoVideos
  case VideosAwaitingFile(count: Int)

  /** Advertiser videos in rotation whose advertiser is retired, so the selection never draws them.
    */
  case AdVideosStranded(count: Int)

  /** Advertiser videos whose survey has no active question, which are never served. */
  case AdVideosWithoutSurvey(count: Int)
  case NoStyles
  case NoIcon

  /** Every guest is turned away as they arrive, whatever the UniFi switch says. */
  case NoAccessPoint
end ReadinessStep

object LocationReadinessQuery:
  /** The steps `r` leaves, in the order worth doing them. Reported rather than enforced: a location
    * with no video is a perfectly good location to keep translating; the point is that the operator
    * is told, not stopped.
    *
    * The identification surveys come first, because they block everything after them: a location
    * complete in every other respect still shows nobody anything without the email survey. The
    * terms next, since a deploy is refused without them. Advertisers before videos, which need one.
    * Branding and the access point last.
    */
  def nextSteps(r: ReadinessReport): List[ReadinessStep] =
    val identification =
      Option
        .when(r.missingIdentificationSurveys.nonEmpty)(
          ReadinessStep.IdentificationSurveysMissing(r.missingIdentificationSurveys))
        .toList
    val terms =
      r.termsVersion match
        case None =>
          List(ReadinessStep.NoTerms)
        case Some(_) if r.localesWithoutTerms.nonEmpty =>
          List(ReadinessStep.TermsWithoutLocales(r.localesWithoutTerms))
        case _ =>
          Nil
    val translations =
      if r.locales.isEmpty then
        List(ReadinessStep.NoTranslations)
      else
        r.locales
          .filter(_.missing > 0)
          .map(gap => ReadinessStep.LocaleIncomplete(gap.locale, gap.missing, r.requiredKeys))
    val advertisers = Option.when(r.advertisers == 0)(ReadinessStep.NoAdvertisers).toList
    val videos =
      if r.activeVideos == 0 && r.pendingVideos == 0 then
        List(ReadinessStep.NoVideos)
      else if r.pendingVideos > 0 then
        List(ReadinessStep.VideosAwaitingFile(r.pendingVideos))
      else
        Nil
    // Apart from the count of advertisers: there can be plenty active and none of them the owner of a
    // video here.
    val stranded =
      Option.when(r.strandedAdVideos > 0)(ReadinessStep.AdVideosStranded(r.strandedAdVideos)).toList
    // Against `adVideos`, not `activeVideos`: a promo plays with or without a survey.
    val surveys =
      Option
        .when(r.adVideos > r.advertiserSurveys)(
          ReadinessStep.AdVideosWithoutSurvey(r.adVideos - r.advertiserSurveys))
        .toList
    val branding =
      Option.when(!r.hasStyles)(ReadinessStep.NoStyles).toList ++
        Option.when(!r.hasIcon)(ReadinessStep.NoIcon).toList
    val accessPoint = Option.when(!r.unifiConfigured)(ReadinessStep.NoAccessPoint).toList
    identification ++ terms ++ translations ++ advertisers ++ videos ++ stranded ++ surveys ++
      branding ++ accessPoint
  end nextSteps
end LocationReadinessQuery
