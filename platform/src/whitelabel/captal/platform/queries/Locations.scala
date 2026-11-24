package whitelabel.captal.platform.queries

import cats.data.NonEmptyChain
import io.getquill.*
import whitelabel.captal.core.application.Error
import whitelabel.captal.core.application.queries.{
  AccessPointReport,
  AccessPointsOfQuery,
  AccessPointsQuery,
  AccessPointsReport,
  ControllerReport,
  ControllersQuery,
  KnownAccessPoint,
  ListLocationsQuery,
  LocationPreview,
  LocationSummary,
  PreviewLocationQuery,
  Query,
  Taker
}
import whitelabel.captal.core.i18n.{I18n, I18nSchema}
import whitelabel.captal.core.location.{Error as LocationError, Slug}
import whitelabel.captal.platform.aws.{Asset, AssetStore}
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.services.LocaleServiceQuill
import whitelabel.captal.platform.{
  AccessPointRow,
  ActiveAccessPointRow,
  LocalizedTextRow,
  LocationAccessPointRow,
  LocationRow,
  UnifiControllerRow
}
import zio.*

/** [[ListLocationsQuery]], [[AccessPointsOfQuery]], [[ControllersQuery]], [[AccessPointsQuery]] and
  * [[PreviewLocationQuery]].
  */
object Locations:
  def list(quill: QuillSqlite): Query.Aux[Task, ListLocationsQuery, List[LocationSummary]] =
    new Query[Task, ListLocationsQuery]:
      type Result = List[LocationSummary]

      def execute(q: ListLocationsQuery): Task[Either[Error, List[LocationSummary]]] =
        import quill.*
        for
          rows <- run(query[LocationRow])
          // An access point is stored only with its controller's settings complete.
          configured <- run(query[LocationAccessPointRow].map(_.locationId)).map(_.toSet)
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
          for
            points <- run(
              for
                configured <- query[LocationAccessPointRow].filter(
                  _.locationId == lift(location.id))
                point      <- query[AccessPointRow].join(_.id == configured.accessPointId)
                controller <- query[UnifiControllerRow].join(_.id == point.controllerId)
              yield (configured, point, controller))
            takers <- takersOf(quill, points.map(_._2.id).toSet)
          yield AccessPointsReport(
            location.slug,
            location.name,
            points
              .sortBy(_._2.mac)
              .map((configured, point, controller) =>
                AccessPointReport(
                  apMac = point.mac,
                  controllerId = controller.id,
                  host = controller.host,
                  port = controller.port,
                  siteId = controller.siteId,
                  // Whether, never what. The value leaves the backoffice only towards the
                  // controller.
                  tokenSet = controller.apiToken.nonEmpty,
                  durationMinutes = configured.durationMinutes,
                  redirectUrl = configured.redirectUrl,
                  enabled = configured.unifiIsEnabled == 1,
                  takenBy = takers.get(point.id)
                ))
          )
      end execute

  def controllers(quill: QuillSqlite): Query.Aux[Task, ControllersQuery, List[ControllerReport]] =
    new Query[Task, ControllersQuery]:
      type Result = List[ControllerReport]

      def execute(q: ControllersQuery): Task[Either[Error, List[ControllerReport]]] =
        import quill.*
        for
          controllers <- run(query[UnifiControllerRow])
          // The access points a location is configured with, as the controller's removal counts
          // them.
          uses <- run(
            query[AccessPointRow]
              .filter(point =>
                query[LocationAccessPointRow].filter(_.accessPointId == point.id).nonEmpty)
              .map(_.controllerId))
        yield Right(
          controllers
            .sortBy(row => (row.host, row.siteId, row.id))
            .map(row =>
              ControllerReport(
                id = row.id,
                host = row.host,
                port = row.port,
                siteId = row.siteId,
                tokenSet = row.apiToken.nonEmpty,
                accessPoints = uses.count(_ == row.id))))
        end for
      end execute

  def known(quill: QuillSqlite): Query.Aux[Task, AccessPointsQuery, List[KnownAccessPoint]] =
    new Query[Task, AccessPointsQuery]:
      type Result = List[KnownAccessPoint]

      def execute(q: AccessPointsQuery): Task[Either[Error, List[KnownAccessPoint]]] =
        import quill.*
        for
          points     <- run(query[AccessPointRow])
          configured <- run(
            for
              link     <- query[LocationAccessPointRow]
              location <- query[LocationRow].join(_.id == link.locationId)
            yield (link.accessPointId, location.slug))
          takers <- takersOf(quill, points.map(_.id).toSet)
        yield Right(
          points
            .sortBy(_.mac)
            .map(point =>
              KnownAccessPoint(
                mac = point.mac,
                controllerId = point.controllerId,
                locations =
                  configured
                    .collect { case (point.id, slug) =>
                      slug
                    }
                    .sorted,
                takenBy = takers.get(point.id))))
        end for
      end execute

  /** The location that took each of the access points `ids`, by access point id. */
  private def takersOf(quill: QuillSqlite, ids: Set[String]): Task[Map[String, Taker]] =
    import quill.*
    run(
      for
        taken <- query[ActiveAccessPointRow].filter(row =>
          liftQuery(ids).contains(row.accessPointId))
        location <- query[LocationRow].join(_.id == taken.locationId)
      yield (taken.accessPointId, location.slug, taken.state)).map(
      _.map((id, slug, state) => id -> Taker(slug, state)).toMap)

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
