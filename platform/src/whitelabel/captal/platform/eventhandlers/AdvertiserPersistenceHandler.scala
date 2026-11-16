package whitelabel.captal.platform.eventhandlers

import io.getquill.*
import whitelabel.captal.core.advertiser.Event as AdvertiserEvent
import whitelabel.captal.core.application.Event
import whitelabel.captal.core.survey.AdvertiserId
import whitelabel.captal.platform.AdvertiserRow
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import zio.*

/** The advertisers, as the backoffice's commands decided them. Retiring one keeps its row, its
  * videos and the answers given to them; the rotation reads `is_active` to leave it out.
  */
object AdvertiserPersistenceHandler:
  def apply(): DbEventHandler[Event] =
    new DbEventHandler[Event]:
      def handle(events: List[Event], quill: QuillSqlite): Task[Unit] =
        import quill.*
        def activeFlag(id: AdvertiserId, active: Int, at: String) =
          run(
            query[AdvertiserRow]
              .filter(_.id == lift(id.asString))
              .update(_.isActive -> lift(active), _.updatedAt -> lift(at))).unit
        ZIO.foreachDiscard(events):
          case Event.Advertiser(AdvertiserEvent.AdvertiserCreated(advertiser, occurredAt)) =>
            val at = occurredAt.toString
            run(
              query[AdvertiserRow].insertValue(
                lift(
                  AdvertiserRow(
                    advertiser.id.asString,
                    advertiser.name,
                    advertiser.priority,
                    1,
                    at,
                    at)))).unit
          case Event.Advertiser(
                AdvertiserEvent.AdvertiserUpdated(id, name, priority, occurredAt)) =>
            run(
              query[AdvertiserRow]
                .filter(_.id == lift(id.asString))
                .update(
                  _.name      -> lift(name),
                  _.priority  -> lift(priority),
                  _.updatedAt -> lift(occurredAt.toString))).unit
          case Event.Advertiser(AdvertiserEvent.AdvertiserRetired(id, occurredAt)) =>
            activeFlag(id, 0, occurredAt.toString)
          case Event.Advertiser(AdvertiserEvent.AdvertiserReinstated(id, occurredAt)) =>
            activeFlag(id, 1, occurredAt.toString)
          case _ =>
            ZIO.unit
      end handle
end AdvertiserPersistenceHandler
