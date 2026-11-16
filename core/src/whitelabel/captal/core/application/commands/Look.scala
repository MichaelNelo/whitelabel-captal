package whitelabel.captal.core.application.commands

import java.time.Instant

import cats.Monad
import whitelabel.captal.core.infrastructure.LocationRepository
import whitelabel.captal.core.location.{Error as LocationError, Event as LocationEvent, Slug}

/** Replaces the stylesheet of the location `slug` with `css`, whole: not a patch. Whether it is CSS
  * is the caller's to check — file formats stay out of `core`.
  */
final case class ChangeStylesCommand(slug: Slug, css: String, occurredAt: Instant)

/** Refused for a stylesheet with nothing in it. What the portal serves changes once the stylesheet
  * is published, after the change is recorded.
  */
object ChangeStylesHandler:
  def apply[F[_]: Monad](
      locations: LocationRepository[F]): Handler.Aux[F, ChangeStylesCommand, Unit] =
    new Handler[F, ChangeStylesCommand]:
      type Result = Unit

      def handle(cmd: ChangeStylesCommand) =
        Decided.located(locations, cmd.slug): location =>
          Monad[F].pure(
            Decided:
              for _ <- Decided.unless(Option.when(cmd.css.isBlank)(LocationError.BlankStylesheet))
              yield (
                LocationEvent.StylesChanged(location.id, location.slug, cmd.css, cmd.occurredAt),
                ()))
end ChangeStylesHandler

/** Replaces the brand icon of the location `slug` with the SVG document `svg`, whole. Whether it is
  * SVG, and safe to serve, is the caller's to check.
  */
final case class ChangeIconCommand(slug: Slug, svg: String, occurredAt: Instant)

/** Refused for an icon with nothing in it. */
object ChangeIconHandler:
  def apply[F[_]: Monad](
      locations: LocationRepository[F]): Handler.Aux[F, ChangeIconCommand, Unit] =
    new Handler[F, ChangeIconCommand]:
      type Result = Unit

      def handle(cmd: ChangeIconCommand) =
        Decided.located(locations, cmd.slug): location =>
          Monad[F].pure(
            Decided:
              for _ <- Decided.unless(Option.when(cmd.svg.isBlank)(LocationError.BlankIcon))
              yield (
                LocationEvent.IconChanged(location.id, location.slug, cmd.svg, cmd.occurredAt),
                ()))
end ChangeIconHandler
