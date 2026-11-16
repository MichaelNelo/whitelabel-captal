package whitelabel.captal.platform.queries

import cats.data.NonEmptyChain
import io.getquill.*
import whitelabel.captal.core.application.Error
import whitelabel.captal.core.application.queries.{
  AccessPointReport,
  AccessPointsOfQuery,
  AccessPointsReport,
  ListLocationsQuery,
  LocationPreview,
  LocationSummary,
  PreviewLocationQuery,
  Query
}
import whitelabel.captal.core.i18n.{I18n, I18nSchema}
import whitelabel.captal.core.location.{Error as LocationError, Slug}
import whitelabel.captal.platform.aws.{Asset, AssetStore}
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.services.LocaleServiceQuill
import whitelabel.captal.platform.{AccessPointRow, LocalizedTextRow, LocationRow}
import zio.*

/** [[ListLocationsQuery]], [[AccessPointsOfQuery]] and [[PreviewLocationQuery]]. */
object Locations:
  def list(quill: QuillSqlite): Query.Aux[Task, ListLocationsQuery, List[LocationSummary]] =
    new Query[Task, ListLocationsQuery]:
      type Result = List[LocationSummary]

      def execute(q: ListLocationsQuery): Task[Either[Error, List[LocationSummary]]] =
        import quill.*
        for
          rows <- run(query[LocationRow])
          // An access point is stored only with its controller's settings complete.
          configured <- run(query[AccessPointRow].map(_.locationId)).map(_.toSet)
        yield Right(
          rows
            .filter(r => q.includeInactive || r.isActive == 1)
            .sortBy(_.slug)
            .map(r =>
              LocationSummary(
                r.slug,
                r.name,
                r.isActive == 1,
                configured.contains(r.id),
                r.fingerprintIsRequired == 1)))

  def accessPoints(quill: QuillSqlite): Query.Aux[Task, AccessPointsOfQuery, AccessPointsReport] =
    new Query[Task, AccessPointsOfQuery]:
      type Result = AccessPointsReport

      def execute(q: AccessPointsOfQuery): Task[Either[Error, AccessPointsReport]] =
        import quill.*
        located(quill, q.slug): location =>
          run(query[AccessPointRow].filter(_.locationId == lift(location.id))).map: points =>
            AccessPointsReport(
              location.slug,
              location.name,
              points
                .sortBy(_.mac)
                .map(point =>
                  AccessPointReport(
                    apMac = point.mac,
                    host = point.unifiHost,
                    port = point.unifiPort,
                    siteId = point.unifiSiteId,
                    // Whether, never what. The value leaves the backoffice only towards the
                    // controller.
                    tokenSet = point.unifiApiToken.nonEmpty,
                    durationMinutes = point.durationMinutes,
                    redirectUrl = point.redirectUrl,
                    enabled = point.unifiIsEnabled == 1
                  ))
            )
      end execute

  def preview(
      quill: QuillSqlite,
      store: AssetStore): Query.Aux[Task, PreviewLocationQuery, LocationPreview] =
    new Query[Task, PreviewLocationQuery]:
      type Result = LocationPreview

      def execute(q: PreviewLocationQuery): Task[Either[Error, LocationPreview]] =
        import quill.*
        val slug = q.slug.asString
        located(quill, q.slug): location =>
          val service = LocaleServiceQuill(quill, Some(location.id))
          for
            i18n       <- service.getI18n(q.locale)
            locales    <- service.listAvailable()
            translated <- run(
              query[LocalizedTextRow]
                .filter(r =>
                  r.locationId.contains(lift(location.id)) && r.locale == lift(q.locale) &&
                    r.category == "frontend" && r.isActive == 1)
                .map(_.entityId))
            styled  <- store.exists(store.key(slug, Asset.Styles)).mapError(Exception(_))
            branded <- store.exists(store.key(slug, Asset.BrandIcon)).mapError(Exception(_))
          yield LocationPreview(
            slug = location.slug,
            name = location.name,
            locale = q.locale,
            stylesheet = Option.when(styled)(store.publicUrl(store.key(slug, Asset.Styles))),
            icon =
              if branded then
                store.publicUrl(store.key(slug, Asset.BrandIcon))
              else
                store.publicUrl(s"bundle/${Asset.BrandIcon.fileName}")
            ,
            texts = I18nSchema.flatten(i18n),
            locales = (q.locale :: locales).distinct,
            untranslated = I18nSchema.keys[I18n].count(key => !translated.contains(key))
          )
          end for
      end execute

  /** The location `slug` decides with, or refused when there is none. */
  private def located[A](quill: QuillSqlite, slug: Slug)(
      decide: LocationRow => Task[A]): Task[Either[Error, A]] =
    import quill.*
    run(query[LocationRow].filter(_.slug == lift(slug.asString))).flatMap:
      _.headOption match
        case None =>
          ZIO.left(Error.Location(NonEmptyChain.one(LocationError.NotFound(slug))))
        case Some(location) =>
          decide(location).map(Right(_))
end Locations
