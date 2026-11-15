package whitelabel.captal.platform.queries

import cats.data.NonEmptyChain
import io.getquill.*
import whitelabel.captal.core.application.Error
import whitelabel.captal.core.application.queries.{
  LocaleGap,
  LocationReadinessQuery,
  Query,
  ReadinessReport
}
import whitelabel.captal.core.i18n.{I18n, I18nSchema}
import whitelabel.captal.core.location.{Error as LocationError, Slug}
import whitelabel.captal.core.video.VideoType
import whitelabel.captal.platform.aws.{Asset, AssetStore}
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.core.given
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.{
  AccessPointRow,
  AdvertiserRow,
  AdvertiserVideoRow,
  LocalizedTextRow,
  LocationRow,
  QuestionRow,
  SurveyCategory,
  SurveyRow,
  Terms,
  VideoStatus
}
import zio.*

/** [[LocationReadinessQuery]], read from the database and from the location's objects in S3, with
  * its steps from `core`'s rule.
  *
  * Refused, with nothing read, for a slug of the wrong shape or one no location has.
  */
object LocationReadiness:
  private val requiredKeys: List[String] = summon[I18nSchema[I18n]].keys("")

  /** The three surveys a guest is identified through, deployment-wide. */
  private val IdentificationCategories = List(
    SurveyCategory.Email,
    SurveyCategory.Profiling,
    SurveyCategory.Location)

  private inline def textsForLocation = quote: (locationIdParam: String) =>
    query[LocalizedTextRow].filter(t =>
      t.locationId.contains(locationIdParam) && t.category == "frontend" && t.isActive == 1)

  def apply(
      quill: QuillSqlite,
      assets: AssetStore): Query.Aux[Task, LocationReadinessQuery, ReadinessReport] =
    new Query[Task, LocationReadinessQuery]:
      type Result = ReadinessReport

      def execute(q: LocationReadinessQuery): Task[Either[Error, ReadinessReport]] =
        import quill.*
        Slug.parse(q.slug) match
          case Left(invalid) =>
            ZIO.succeed(Left(Error.Location(NonEmptyChain.one(invalid))))
          case Right(slug) =>
            run(query[LocationRow].filter(_.slug == lift(slug.asString))).flatMap:
              _.headOption match
                case None =>
                  ZIO.succeed(Left(Error.Location(NonEmptyChain.one(LocationError.NotFound(slug)))))
                case Some(row) =>
                  report(quill, assets, row).map(Right(_))

  private def report(
      quill: QuillSqlite,
      assets: AssetStore,
      row: LocationRow): Task[ReadinessReport] =
    import quill.*
    for
      texts       <- run(textsForLocation(lift(row.id)))
      points      <- run(query[AccessPointRow].filter(_.locationId == lift(row.id)))
      videos      <- run(query[AdvertiserVideoRow])
      surveys     <- run(query[SurveyRow])
      advertisers <- run(query[AdvertiserRow])
      questions   <- run(query[QuestionRow])
      terms       <- Terms.current(quill)
      termLocales <- Terms.locales(quill)
      styles <- assets.exists(assets.key(row.slug, Asset.Styles)).mapError(new RuntimeException(_))
      icon <- assets.exists(assets.key(row.slug, Asset.BrandIcon)).mapError(new RuntimeException(_))
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
      facts = ReadinessReport(
        slug = row.slug,
        name = row.name,
        active = row.isActive == 1,
        // An access point is stored only with all of host, site id and token, so having one is
        // being configured.
        unifiConfigured = points.nonEmpty,
        unifiEnabled = points.exists(_.unifiIsEnabled == 1),
        locales = byLocale,
        requiredKeys = requiredKeys.size,
        hasStyles = styles,
        hasIcon = icon,
        activeVideos = inRotation.size,
        pendingVideos = mine.count(_.status == VideoStatus.Pending),
        advertiserSurveys = scoped.count(s =>
          s.category == SurveyCategory.Advertiser &&
            questions.exists(q => q.surveyId == s.id && q.isActive == 1)),
        adVideos = inRotation.count(_.videoType == VideoType.Ad),
        // The join the ad selection does, counted here: `nextAdQueryAuthenticated` requires the
        // advertiser to be active, so a video whose advertiser was retired is drawn by nothing.
        // `advertisers` below cannot show this — it counts active advertisers across the whole
        // deployment, so a location full of stranded videos reads as healthy beside it.
        strandedAdVideos = inRotation.count(v =>
          v.videoType == VideoType.Ad && !v.advertiserId.exists(activeAdvertiserIds.contains)),
        advertisers = advertisers.count(_.isActive == 1),
        // Deployment-wide, so deliberately not filtered by location: an active one anywhere serves
        // every portal. Usable, not merely present: `firstEmailSurveyQuery` and
        // `nextIdentificationSurveyQuery` both join `questions` and filter on active, so a survey
        // whose questions were all retired returns no rows and the guest is never identified.
        missingIdentificationSurveys = IdentificationCategories.filterNot(category =>
          surveys.exists(s =>
            s.category == category && s.isActive == 1 &&
              questions.exists(q => q.surveyId == s.id && q.isActive == 1))),
        termsVersion = terms.map(_.version),
        localesWithoutTerms =
          if terms.isEmpty then
            Nil
          else
            byLocale.map(_.locale).filterNot(termLocales.contains)
        ,
        nextSteps = Nil
      )
    yield facts.copy(nextSteps = LocationReadinessQuery.nextSteps(facts))
    end for
  end report
end LocationReadiness
