package whitelabel.captal.mcp.tools

import cats.data.NonEmptyChain
import cats.syntax.foldable.*
import whitelabel.captal.core.advertiser.Error as AdvertiserError
import whitelabel.captal.core.application.Error as AppError
import whitelabel.captal.core.i18n.Error as I18nError
import whitelabel.captal.core.location.{Error as LocationError, Slug}
import whitelabel.captal.core.recovery.Error as RecoveryError
import whitelabel.captal.core.survey.authoring.{Error as SurveyError, TextField}
import whitelabel.captal.core.terms.Error as TermsError
import whitelabel.captal.core.video.authoring.{Error as VideoError, VideoText}
import whitelabel.captal.platform.repositories.SurveyAuthoringRepositoryQuill
import zio.*

/** What the operator reads when a command or a query is refused: each domain's errors described
  * once, and every domain described, so a tool cannot leave one to its `toString`. The texts are
  * the backoffice's — they name its tools — which is why they live here and not beside the errors
  * in `core`, whose guest-facing routes describe the same errors otherwise.
  */
object Refusals:
  /** Which of a pair of survey question tools a call came through — the deployment-wide
    * identification surveys' or a location's — and the other one, which takes what this one
    * refuses.
    */
  final case class SurveySide(identification: Boolean, otherTool: String)

  def describe(error: AppError): String = describing(None)(error)

  /** As [[describe]], naming `side`'s other tool for a survey on the other side. */
  def describing(side: Option[SurveySide])(error: AppError): String =
    error match
      case AppError.Location(found) =>
        all(found)(location)
      case AppError.Advertiser(found) =>
        all(found)(advertiser)
      case AppError.SurveyAuthoring(found) =>
        all(found)(survey(side))
      case AppError.VideoAuthoring(found) =>
        all(found)(video)
      case AppError.Terms(found) =>
        all(found)(terms)
      case AppError.I18n(found) =>
        all(found)(i18n)
      case AppError.Recovery(found) =>
        all(found)(recovery)
      // The guest's: no backoffice command or query is refused with them.
      case guest @ (
            AppError.NoSurveyAssigned | AppError.UserNotIdentified | AppError.TermsNotAccepted |
            AppError.NoTermsOfService | AppError.InvalidEmailFormat(_) | AppError.Survey(_) |
            AppError.User(_) | AppError.Video(_) | AppError.InvalidClient(_) | AppError
              .UnknownAccessPoint(_) | AppError.LocationNotDeployed(_)
          ) =>
        guest.toString

  /** The slug `raw` names, refused as a command would refuse it. */
  def slugOf(raw: String): IO[String, Slug] = ZIO.fromEither(Slug.parse(raw)).mapError(location)

  private def all[E](found: NonEmptyChain[E])(each: E => String): String = found
    .toList
    .map(each)
    .mkString(" ")

  private[mcp] def location(error: LocationError): String =
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

  private def advertiser(error: AdvertiserError): String =
    error match
      case AdvertiserError.NotFound(id) =>
        s"No advertiser with id '$id'. Call list_advertisers to see what exists."
      case AdvertiserError.BlankName =>
        "Name must not be blank."
      case AdvertiserError.NegativePriority(priority) =>
        s"Priority must be zero or greater, got $priority."
      case AdvertiserError.NameTaken(name, by) =>
        s"An advertiser named '$name' already exists (id ${by
            .asString}). Use update_advertiser to change it."
      case AdvertiserError.NothingToUpdate =>
        "Nothing to update: give a name, a priority, or both."

  private[mcp] def survey(side: Option[SurveySide])(error: SurveyError): String =
    def field(text: TextField) =
      text match
        case TextField.Text =>
          "text"
        case TextField.Description =>
          "description"
        case TextField.Placeholder =>
          "placeholder"
        case TextField.OptionText(index) =>
          s"option $index"
    error match
      case SurveyError.SurveyNotFound(id) =>
        s"No survey with id '$id'. Call list_surveys to see what exists."
      case SurveyError.QuestionNotFound(id) =>
        s"No question with id '$id'. Call get_survey to see what a survey contains."
      case SurveyError.VideoNotFound(id) =>
        s"No video with id '$id'."
      case SurveyError.IdentificationSurveyExists(kind, existing) =>
        s"An active '${SurveyAuthoringRepositoryQuill.categoryOf(
            kind)}' survey already exists (id ${existing
            .asString}). Identification surveys are global and picked by category, so two would be ambiguous."
      case SurveyError.OtherSide(kind) =>
        val category = SurveyAuthoringRepositoryQuill.categoryOf(kind)
        side match
          case Some(SurveySide(true, other)) =>
            s"That question belongs to a '$category' survey, which is scoped to a location. Use $other."
          case Some(SurveySide(false, other)) =>
            s"That question belongs to the deployment-wide '$category' survey, asked of every guest of every location. Use $other, which requires identity:write."
          case None =>
            s"That question belongs to a '$category' survey, which this tool does not take."
      case SurveyError.NoText =>
        "A question needs text in at least one locale, or it cannot be rendered."
      case SurveyError.BlankText(text) =>
        s"${field(text)} has an entry with a blank locale or value."
      case SurveyError.RepeatedLocales(text, locales) =>
        s"${field(text)} repeats the locale(s) ${locales.mkString(", ")}."
      case SurveyError.NoOptions =>
        "A radio, dropdown or checkbox question needs at least one option."
      case SurveyError.OptionWithoutText(index) =>
        s"Option $index has no text."
      case SurveyError.HierarchyOutsideLocation =>
        "hierarchyLevel only applies to questions in a location survey."
      case SurveyError.NegativePoints(points) =>
        s"Points must be zero or greater, got $points."
      case SurveyError.NotEveryQuestion(missing, extra) =>
        s"The list must name every active question exactly once. Missing: ${missing.mkString(
            ", ")}. Not in this survey or not active: ${extra.mkString(", ")}."
      case SurveyError.RepeatedQuestion =>
        "The list repeats a question."
      case SurveyError.NoQuestions(id) =>
        s"No survey with id '$id', or it has no questions."
    end match
  end survey

  private def video(error: VideoError): String =
    def field(text: VideoText) =
      text match
        case VideoText.Title =>
          "title"
        case VideoText.Description =>
          "description"
    error match
      case VideoError.VideoNotFound(id) =>
        s"No video with id '$id'. Call list_videos to see what exists."
      case VideoError.AdvertiserNotFound(id) =>
        s"No advertiser with id '${id.asString}'. Call list_advertisers."
      case VideoError.AdvertiserRetired(name) =>
        s"Advertiser '$name' is retired, so its videos would never be drawn. Reactivate it first."
      case VideoError.NoTitle =>
        "The video needs a title in at least one locale."
      case VideoError.BlankText(text) =>
        s"${field(text)} has an entry with a blank locale or value."
      case VideoError.RepeatedLocales(text, locales) =>
        s"${field(text)} repeats the locale(s) ${locales.mkString(", ")}."
      case VideoError.NoQuestions =>
        "A video needs at least one question. The rotation only serves a video whose survey still has something to ask, so one without any is never shown."
      case VideoError.QuestionRefused(index, refusal) =>
        s"Question $index: ${survey(None)(refusal)}"
      case VideoError.MinWatchNotPositive(seconds) =>
        s"minWatchSeconds must be at least 1, got $seconds. Omit it for the whole video."
      case VideoError.NegativePriority(priority) =>
        s"priority must be zero or greater, got $priority."
      case VideoError.NegativeNoRepeat(seconds) =>
        s"noRepeatSeconds cannot be negative, got $seconds. Omit it for no limit."
      case VideoError.DurationNotPositive(seconds) =>
        s"durationSeconds must be at least 1, got $seconds. The upload page reads it from the file; if it could not, ask the operator and pass it here."
      case VideoError.NoFileWaiting(id) =>
        s"Video '${id
            .asString}' has no file waiting: it plays the one it has. replace_video_file starts a new one."
      case VideoError.FileNoLongerWaiting(id, _) =>
        s"Video '${id
            .asString}' no longer waits for that file: another replaced it meanwhile. Call resume_video_upload for the one it waits for."
      case VideoError.StillPending(id) =>
        s"Video '${id
            .asString}' is still waiting for its first file. Upload it and call confirm_video_upload, or call resume_video_upload for a fresh signed URL."
      case VideoError.NoLocation(id) =>
        s"Video '${id.asString}' belongs to no location, so its file has no key."
    end match
  end video

  private def terms(error: TermsError): String =
    error match
      case TermsError.NoTermsYet =>
        "No terms of service exist yet. Create the first version with create_terms_version."
      case TermsError.NoTranslations =>
        "Give the new version at least one translation."
      case TermsError.BlankLocale =>
        "Every translation needs a locale."
      case TermsError.BlankText(locales) =>
        s"The text for ${locales.mkString(", ")} is empty."
      case TermsError.RepeatedLocales(locales) =>
        s"${locales.mkString(", ")} appear more than once."
      case TermsError.DroppedLocales(locales) =>
        s"The version in force is written in ${locales.mkString(
            ", ")} too: a new version needs a text in every locale the previous one had."
      case TermsError.NoSuchTranslation(locale, has) =>
        s"The version in force has no '$locale' text. It has ${has.mkString(", ")}."
      case TermsError.LastTranslation(_) =>
        "That is the version's only text: without one, no portal could show the terms."

  private def i18n(error: I18nError): String =
    error match
      case I18nError.UnknownKeys(keys) =>
        s"Not part of the i18n schema: ${keys.mkString(
            ", ")}. Call get_i18n_schema for the keys that are."
      case I18nError.NoEntries =>
        "No entries given."

  private def recovery(error: RecoveryError): String =
    error match
      case RecoveryError.NothingToChange =>
        "Name at least one setting to change, or one to reset."
      case RecoveryError.SetAndReset(settings) =>
        s"${RecoveryTools.names(settings)} cannot be both set and reset in one call."
      case RecoveryError.Negative(settings) =>
        s"${RecoveryTools.names(settings)} cannot be negative."
      case RecoveryError.CeilingBelowMaximum(ceiling, maximum) =>
        s"ceilingSeconds would be ${ceiling.getSeconds}, below maximumSeconds at ${maximum
            .getSeconds}: no wait could reach the maximum. Set both."
      // The guest's way out of an error: no backoffice command is refused with them.
      case guest @ (
            RecoveryError.UnknownError(_) | RecoveryError.AlreadyRedeemed(_) |
            RecoveryError.NotYet(_)
          ) =>
        guest.toString
end Refusals
