package whitelabel.captal.platform.queries

import java.time.Instant

import io.getquill.*
import whitelabel.captal.core.application.Error
import whitelabel.captal.core.application.queries.{DispatchQuery, DispatchRoute, Query}
import whitelabel.captal.core.device.Mac
import whitelabel.captal.core.location.{Dispatch, Slug}
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.{
  AccessPointRow,
  ActiveAccessPointRow,
  ActiveAccessPoints,
  LocationAccessPointRow,
  LocationRow,
  LocationStates
}
import zio.*

/** [[DispatchQuery]], read from the locations configured with each access point and the one that
  * took it: every location configured with a MAC asked for, as `core`'s [[Dispatch.destination]]
  * reads them. Every access point is read, since there are few, and any number of locations can be
  * configured with one.
  */
object DispatchRoutes:
  def apply(quill: QuillSqlite): Query.Aux[Task, DispatchQuery, List[DispatchRoute]] =
    new Query[Task, DispatchQuery]:
      type Result = List[DispatchRoute]

      def execute(q: DispatchQuery): Task[Either[Error, List[DispatchRoute]]] =
        import quill.*
        for
          rows <- run(
            for
              configured <- query[LocationAccessPointRow]
              point      <- query[AccessPointRow].join(_.id == configured.accessPointId)
              location   <- query[LocationRow].join(_.id == configured.locationId)
            yield (configured, point, location))
          deployed <- run(
            query[ActiveAccessPointRow]
              .filter(_.state == lift(ActiveAccessPoints.Deployed))
              .map(row => (row.accessPointId, row.locationId)))
        yield
          val took = deployed.toSet
          val configured = rows.flatMap((link, point, location) =>
            Mac.parse(point.mac).map(_ -> (link, location)))
          val holders =
            configured
              .groupMap(_._1)(_._2)
              .view
              .mapValues(
                _.map((link, location) =>
                  holder(link, location, took.contains((link.accessPointId, location.id)))))
              .toMap
          val asked =
            q match
              case DispatchQuery.All =>
                holders.keySet
              case DispatchQuery.Of(macs) =>
                macs
              case DispatchQuery.OfLocation(id) =>
                configured
                  .collect {
                    case (mac, (_, location)) if location.id == id.asString =>
                      mac
                  }
                  .toSet
          Right(
            asked
              .toList
              .sortBy(_.toLong)
              .map(mac => DispatchRoute(mac, Dispatch.destination(holders.getOrElse(mac, Nil)))))
        end for
      end execute

  /** `took`: the location took the access point, and its deploy or resume succeeded. */
  private def holder(
      link: LocationAccessPointRow,
      location: LocationRow,
      took: Boolean): Dispatch.Holder = Dispatch.Holder(
    Slug.unsafe(location.slug),
    if took then
      Dispatch.Standing.Deployed
    else if location.state == LocationStates.Paused then
      Dispatch.Standing.Paused
    else
      Dispatch.Standing.Other
    ,
    Instant.parse(location.createdAt),
    link.redirectUrl.map(_.trim).filter(_.nonEmpty)
  )
end DispatchRoutes
