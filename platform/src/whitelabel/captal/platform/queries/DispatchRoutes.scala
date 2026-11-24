package whitelabel.captal.platform.queries

import java.time.Instant

import io.getquill.*
import whitelabel.captal.core.application.Error
import whitelabel.captal.core.application.queries.{DispatchQuery, DispatchRoute, Query}
import whitelabel.captal.core.device.Mac
import whitelabel.captal.core.location.{Dispatch, Slug}
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.{AccessPointRow, LocationRow, LocationStates}
import zio.*

/** [[DispatchQuery]], read from `access_points` and the locations holding them: every holder of a
  * MAC asked for, as `core`'s [[Dispatch.destination]] reads them. Every access point is read,
  * since there are few, and the holders of one MAC can be any location.
  */
object DispatchRoutes:
  def apply(quill: QuillSqlite): Query.Aux[Task, DispatchQuery, List[DispatchRoute]] =
    new Query[Task, DispatchQuery]:
      type Result = List[DispatchRoute]

      def execute(q: DispatchQuery): Task[Either[Error, List[DispatchRoute]]] =
        import quill.*
        run(query[AccessPointRow].join(query[LocationRow]).on(_.locationId == _.id)).map: rows =>
          val held = rows.flatMap((point, location) =>
            Mac.parse(point.mac).map(_ -> (point, location)))
          val holders =
            held
              .groupMap(_._1)(_._2)
              .view
              .mapValues(_.map((point, location) => holder(point, location)))
              .toMap
          val asked =
            q match
              case DispatchQuery.All =>
                holders.keySet
              case DispatchQuery.Of(macs) =>
                macs
              case DispatchQuery.OfLocation(id) =>
                held
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
      end execute

  private def holder(point: AccessPointRow, location: LocationRow): Dispatch.Holder = Dispatch
    .Holder(
      Slug.unsafe(location.slug),
      location.state match
        case LocationStates.Deployed =>
          Dispatch.Standing.Deployed
        case LocationStates.Paused =>
          Dispatch.Standing.Paused
        case _ =>
          Dispatch.Standing.Other
      ,
      Instant.parse(location.createdAt),
      point.redirectUrl.map(_.trim).filter(_.nonEmpty)
    )
end DispatchRoutes
