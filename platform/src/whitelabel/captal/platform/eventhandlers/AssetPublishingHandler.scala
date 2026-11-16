package whitelabel.captal.platform.eventhandlers

import whitelabel.captal.core.application.{Event, EventHandler}
import whitelabel.captal.core.location.Event as LocationEvent
import whitelabel.captal.core.location.Slug
import whitelabel.captal.platform.aws.{Asset, AssetStore}
import zio.*

/** After the commit: a location's new stylesheet or icon, put where its portal reads it, and the
  * edge invalidated so the change shows rather than waiting behind its cache. A write that fails
  * fails the call, the change recorded but not served, for the operator to make again.
  */
object AssetPublishingHandler:
  def apply(store: AssetStore): EventHandler[Task, Event] =
    events =>
      ZIO.foreachDiscard(events):
        case Event.Location(LocationEvent.StylesChanged(_, slug, css, _)) =>
          publish(store, slug, Asset.Styles, css)
        case Event.Location(LocationEvent.IconChanged(_, slug, svg, _)) =>
          publish(store, slug, Asset.BrandIcon, svg)
        case _ =>
          ZIO.unit

  private def publish(store: AssetStore, slug: Slug, asset: Asset, content: String): Task[Unit] =
    (store.put(slug.asString, asset, content) *> store.invalidate(slug.asString, asset))
      .mapError(reason =>
        IllegalStateException(s"Publishing ${asset.fileName} for '${slug.asString}': $reason"))
      .unit
end AssetPublishingHandler
