package whitelabel.captal.mcp.tools

import cats.syntax.foldable.*
import whitelabel.captal.core.application.commands.Handler
import whitelabel.captal.core.application.{Error as AppError, Flow}
import whitelabel.captal.core.infrastructure.{AccessPointRepository, LocationRepository}
import whitelabel.captal.core.location.Error as LocationError
import whitelabel.captal.platform.eventhandlers.{
  EventLogHandler,
  LocationPersistenceHandler,
  TransactionalEventHandler
}
import whitelabel.captal.platform.repositories.LocationRepositoryQuill
import whitelabel.captal.platform.schema.QuillSqlite
import zio.*
import zio.interop.catz.*

/** Where the location tools meet the location's commands: each runs one, as the operator token
  * `actor`, through the chain that writes what it decided and logs it under that token, and turns a
  * refusal into what the operator reads.
  */
object LocationCommands:
  type Repositories = LocationRepository[Task] & AccessPointRepository[Task]

  def run[C, R](actor: String, command: C)(
      handler: Repositories => Handler.Aux[Task, C, R]): ZIO[QuillSqlite, String, R] = ZIO
    .serviceWithZIO[QuillSqlite]: quill =>
      val events = TransactionalEventHandler(
        LocationPersistenceHandler().andThen(EventLogHandler.forOperator(actor)),
        quill)
      Flow(handler(LocationRepositoryQuill(quill)), events)
        .execute(command)
        .catchAll:
          case Flow.HandlerError(errors) =>
            ZIO.fail(
              errors
                .toList
                .flatMap:
                  case AppError.Location(found) =>
                    found.toList.map(describe)
                  case other =>
                    List(other.toString)
                .mkString(" "))
          case defect =>
            ZIO.die(defect)

  /** What the operator reads for each refusal. */
  def describe(error: LocationError): String =
    error match
      case LocationError.InvalidSlug(slug) =>
        s"Slug '$slug' is not kebab-case. Use lowercase letters, digits and single hyphens between them — no spaces, underscores, capitals, or leading, trailing or doubled hyphens."
      case LocationError.BlankName =>
        "Name must not be blank."
      case LocationError.SlugTaken(slug, by) =>
        s"A location with slug '${slug
            .asString}' already exists, named '$by'. Slugs are permanent; pick another one."
      case LocationError.NotFound(slug) =>
        s"No location has the slug '${slug.asString}'. Call list_locations for the slugs."
      case LocationError.InvalidRedirectUrl(url) =>
        s"'$url' no es una URL http o https."
      case LocationError.InvalidDuration(minutes) =>
        s"La duración debe ser de 1 minuto o más, no $minutes."
      case LocationError.BlankHost =>
        "El host no puede quedar vacío: el portal lo necesita para llegar al controlador."
      case LocationError.BlankSiteId =>
        "El site id no puede quedar vacío: el portal lo necesita para llegar al controlador."
      case LocationError.InvalidPort(port) =>
        s"El puerto debe estar entre 1 y 65535, no $port."
      case LocationError.TokenRequired(mac) =>
        s"Falta el API token: el AP ${mac.show} todavía no tiene uno."
      case LocationError.NoSuchAccessPoint(mac) =>
        s"La location no tiene un AP con la MAC ${mac.show}. get_unifi_config los lista."
      case LocationError.AccessPointExists(mac) =>
        s"La location ya tiene un AP con la MAC ${mac.show}."
      case LocationError.AccessPointHeld(mac, by) =>
        s"El AP ${mac.show} ya está en '${by
            .asString}', que está desplegada: un AP lleva a los invitados de una sola location desplegada. Pausala con pause_location antes de usarlo aquí."
      case LocationError.LastAccessPoint(mac) =>
        s"El AP ${mac
            .show} es el único de una location desplegada: sin él, todo invitado sería rechazado. Agregá otro o pausala con pause_location primero."
end LocationCommands
