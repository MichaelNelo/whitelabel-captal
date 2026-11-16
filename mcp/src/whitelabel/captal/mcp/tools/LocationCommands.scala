package whitelabel.captal.mcp.tools

import cats.syntax.foldable.*
import whitelabel.captal.core.application.{Error as AppError, Flow}
import whitelabel.captal.core.location.Error as LocationError
import whitelabel.captal.platform.{Operator, OperatorContext}
import zio.*

/** Where the location tools meet the location's commands: each runs one of [[LocationFlows]], with
  * `operator` published for the handlers, and turns a refusal into what the operator reads.
  */
object LocationCommands:
  def run[C, R](operator: Operator, command: C)(
      flow: Flow.Aux[Task, C, R]): ZIO[OperatorContext, String, R] = ZIO
    .serviceWithZIO[OperatorContext](_.locally(operator)(flow.execute(command)))
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
      case LocationError.AlreadyDeployed(slug) =>
        s"'${slug
            .asString}' is deployed already, so a deploy has nothing to do: a new image or bundle reaches every location through the release. Use pause_location and resume_location to take it out of service and back."
      case LocationError.NotAllowedFrom(state, operation) =>
        s"A ${operation
            .name} does not apply to a location that is $state: deploy from created or destroyed, pause from deployed, resume from paused; destroy from any state."
      case LocationError.NoTermsOfService =>
        "No terms of service exist yet, and a portal cannot start a guest without them. Create the first version with create_terms_version, then try again."
      case LocationError.NoAccessPoint =>
        "The location has no access point, so every guest would be turned away. Register one with configure_unifi, then try again."
      case LocationError.OperationInProgress(run, operation, step) =>
        s"A ${operation.name} of this location is going (run ${run.asString}${step.fold("")(at =>
            s", at «${at.description}»")}). get_deploy_status follows it."
      case LocationError.PreviousOperationUnfinished(run, operation, pending) =>
        s"The ${operation.name} run ${run.asString} was left part way${pending
            .headOption
            .fold("")(at =>
              s", at «${at
                  .description}»")}, so the location stands half changed. Finish it before anything else."
      case LocationError.RunNotStarted(run) =>
        s"The run ${run.asString} is not going: it ended already, or never started."
      case LocationError.NoSuchRun(run) =>
        s"No run with id '$run'."
      case LocationError.NoRuns(slug) =>
        s"No deploy, pause, resume or destroy has run for '${slug.asString}'."
      case LocationError.BlankStylesheet =>
        "The stylesheet is empty. To go back to the base theme, say so explicitly."
      case LocationError.BlankIcon =>
        "The icon is empty."
end LocationCommands
