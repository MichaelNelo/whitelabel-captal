package whitelabel.captal.mcp.tools

import java.time.{Duration, Instant}

import cats.syntax.foldable.*
import com.jamesward.ziohttp.mcp.*
import whitelabel.captal.core.application.Error as AppError
import whitelabel.captal.core.application.commands.ChangeRecoveryPolicyCommand
import whitelabel.captal.core.application.queries.{RecoveryPolicyOfQuery, RecoveryPolicyReport}
import whitelabel.captal.core.location.Slug
import whitelabel.captal.core.recovery.{Error as RecoveryError, PolicyOverrides, PolicySetting}
import whitelabel.captal.mcp.access.Auth
import whitelabel.captal.mcp.protocol.{McpSchema, ToolSchema}
import whitelabel.captal.platform.queries.RecoveryPolicyOf
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.{Operator, OperatorContext}
import zio.*
import zio.schema.annotation.description
import zio.schema.{DeriveSchema, Schema}

import McpSchema.given
import ToolSchema.readOnly

/** A location's recovery settings: how long its guests wait for a way out of an error in the
  * portal's own code, and how long a playback its page never ended stays open. Read through
  * `core`'s `RecoveryPolicyOfQuery` and changed by `ChangeRecoveryPolicyCommand`; the portal and
  * the backoffice read them as they need them, so a change applies to the next guest with no
  * redeploy.
  */
object RecoveryTools:

  final case class StatusRequest(
      @description("Which location. Call list_locations for the slugs.")
      slug: String)

  /** What the location applies, whether it set it or takes the default. */
  final case class PolicyView(
      @description("The location this describes.")
      slug: String,
      @description(
        "What a new guest waits for a way out of a client error, with nothing done on this phone before and nothing this visit. Every other wait is this one, scaled by the factors and weights below.")
      maximumSeconds: Long,
      @description("No wait is longer than this, whatever scales it.")
      ceilingSeconds: Long,
      @description("A wait shorter than this is no wait at all.")
      zeroBelowSeconds: Long,
      @description(
        "How much each video this user watched before on this phone shortens the wait: it is multiplied by (1 + videos) to the minus this.")
      videoWeight: Double,
      @description("The same, for each answer to a video's survey.")
      videoAnswerWeight: Double,
      @description("The same, for each identification answer.")
      identificationWeight: Double,
      @description("The same, for each time the email was given.")
      emailWeight: Double,
      @description("Multiplies the wait once the guest gave this visit's identification answers.")
      identifiedFactor: Double,
      @description("Multiplies it again once this visit's video is watched.")
      watchedFactor: Double,
      @description(
        "Each way out this phone took lately lengthens the wait: it is multiplied by (1 + taken) to this.")
      recoveryExponent: Double,
      @description(
        "Multiplies the wait for a report the server finds made up, such as one thrown from the browser's console.")
      fabricatedFactor: Double,
      @description(
        "Multiplies the wait for an error other phones reported too, including a wait already under way when the second one arrives.")
      recurrentFactor: Double,
      @description("How far back the ways out this phone took count.")
      recoveryWindowSeconds: Long,
      @description("How far back other phones' reports of the same error count.")
      recurrenceWindowSeconds: Long,
      @description(
        "How long after its file would have finished a playback whose page never said it ended is closed. A replaced file is deleted only once no playback holds it.")
      playbackGraceSeconds: Long,
      @description(
        "The settings this location takes from the defaults, and follows if they change. The others it set itself.")
      defaults: List[String])

  final case class SetRequest(
      @description("Which location. Call list_locations for the slugs.")
      slug: String,
      @description(
        "What a new guest waits for a way out of a client error, in seconds. The default is 300.")
      maximumSeconds: Option[Int] = None,
      @description(
        "The longest any wait can be, in seconds; at least maximumSeconds. The default is 1800.")
      ceilingSeconds: Option[Int] = None,
      @description("Waits shorter than this are none, in seconds. The default is 5.")
      zeroBelowSeconds: Option[Int] = None,
      @description(
        "Weight of each video watched before on this phone; 0 makes them count for nothing. The default is 1.0.")
      videoWeight: Option[Double] = None,
      @description("Weight of each answer to a video's survey. The default is 0.5.")
      videoAnswerWeight: Option[Double] = None,
      @description("Weight of each identification answer. The default is 0.25.")
      identificationWeight: Option[Double] = None,
      @description("Weight of each time the email was given. The default is 0.05.")
      emailWeight: Option[Double] = None,
      @description("Factor once identified this visit. The default is 0.3.")
      identifiedFactor: Option[Double] = None,
      @description("Further factor once this visit's video is watched. The default is 0.5.")
      watchedFactor: Option[Double] = None,
      @description("Exponent of the ways out taken lately. The default is 2.0.")
      recoveryExponent: Option[Double] = None,
      @description("Factor for a made-up report. The default is 10.0.")
      fabricatedFactor: Option[Double] = None,
      @description("Factor for an error other phones reported too. The default is 0.1.")
      recurrentFactor: Option[Double] = None,
      @description(
        "How far back ways out taken count, in seconds. The default is 2592000, thirty days.")
      recoveryWindowSeconds: Option[Int] = None,
      @description(
        "How far back other phones' reports count, in seconds. The default is 604800, seven days.")
      recurrenceWindowSeconds: Option[Int] = None,
      @description(
        "How long after its file would have finished an abandoned playback is closed, in seconds. The default is 600.")
      playbackGraceSeconds: Option[Int] = None,
      @description(
        "Settings to return to their default, by the names above. A setting cannot be both set and reset in one call.")
      reset: List[String] = Nil)

  given Schema[StatusRequest] = DeriveSchema.gen
  given Schema[PolicyView] = DeriveSchema.gen
  given Schema[SetRequest] = DeriveSchema.gen

  /** Each setting by its name in these tools. */
  private val Names: List[(PolicySetting, String)] = List(
    PolicySetting.Maximum              -> "maximumSeconds",
    PolicySetting.Ceiling              -> "ceilingSeconds",
    PolicySetting.ZeroBelow            -> "zeroBelowSeconds",
    PolicySetting.VideoWeight          -> "videoWeight",
    PolicySetting.VideoAnswerWeight    -> "videoAnswerWeight",
    PolicySetting.IdentificationWeight -> "identificationWeight",
    PolicySetting.EmailWeight          -> "emailWeight",
    PolicySetting.IdentifiedFactor     -> "identifiedFactor",
    PolicySetting.WatchedFactor        -> "watchedFactor",
    PolicySetting.RecoveryExponent     -> "recoveryExponent",
    PolicySetting.FabricatedFactor     -> "fabricatedFactor",
    PolicySetting.RecurrentFactor      -> "recurrentFactor",
    PolicySetting.RecoveryWindow       -> "recoveryWindowSeconds",
    PolicySetting.RecurrenceWindow     -> "recurrenceWindowSeconds",
    PolicySetting.PlaybackGrace        -> "playbackGraceSeconds"
  )

  private def nameOf(setting: PolicySetting): String = Names
    .collectFirst { case (`setting`, name) =>
      name
    }
    .getOrElse(setting.toString)

  private def names(settings: List[PolicySetting]): String = settings.map(nameOf).mkString(", ")

  /** The settings `reset` names, or which of its names no setting has. */
  private def resetOf(reset: List[String]): IO[String, Set[PolicySetting]] =
    val byName = Names.map(_.swap).toMap
    reset.filterNot(byName.contains) match
      case Nil =>
        ZIO.succeed(reset.map(byName).toSet)
      case unknown =>
        ZIO.fail(
          s"No setting is named ${unknown.mkString(", ")}. They are: ${Names
              .map(_._2)
              .mkString(", ")}.")

  private def requested(in: SetRequest): PolicyOverrides =
    def seconds(value: Option[Int]) = value.map(s => Duration.ofSeconds(s.toLong))
    PolicyOverrides(
      maximum = seconds(in.maximumSeconds),
      ceiling = seconds(in.ceilingSeconds),
      zeroBelow = seconds(in.zeroBelowSeconds),
      videoWeight = in.videoWeight,
      videoAnswerWeight = in.videoAnswerWeight,
      identificationWeight = in.identificationWeight,
      emailWeight = in.emailWeight,
      identifiedFactor = in.identifiedFactor,
      watchedFactor = in.watchedFactor,
      recoveryExponent = in.recoveryExponent,
      fabricatedFactor = in.fabricatedFactor,
      recurrentFactor = in.recurrentFactor,
      recoveryWindow = seconds(in.recoveryWindowSeconds),
      recurrenceWindow = seconds(in.recurrenceWindowSeconds),
      playbackGrace = seconds(in.playbackGraceSeconds)
    )

  private def viewOf(slug: Slug, report: RecoveryPolicyReport): PolicyView =
    val policy = report.policy
    PolicyView(
      slug = slug.asString,
      maximumSeconds = policy.maximum.getSeconds,
      ceilingSeconds = policy.ceiling.getSeconds,
      zeroBelowSeconds = policy.zeroBelow.getSeconds,
      videoWeight = policy.videoWeight,
      videoAnswerWeight = policy.videoAnswerWeight,
      identificationWeight = policy.identificationWeight,
      emailWeight = policy.emailWeight,
      identifiedFactor = policy.identifiedFactor,
      watchedFactor = policy.watchedFactor,
      recoveryExponent = policy.recoveryExponent,
      fabricatedFactor = policy.fabricatedFactor,
      recurrentFactor = policy.recurrentFactor,
      recoveryWindowSeconds = policy.recoveryWindow.getSeconds,
      recurrenceWindowSeconds = policy.recurrenceWindow.getSeconds,
      playbackGraceSeconds = report.grace.getSeconds,
      defaults = report.defaults.map(nameOf)
    )
  end viewOf

  /** What the operator reads for each refusal of these tools. */
  private val describe: PartialFunction[AppError, List[String]] =
    case AppError.Location(found) =>
      found.toList.map(LocationCommands.describe)
    case AppError.Recovery(found) =>
      found
        .toList
        .map:
          case RecoveryError.NothingToChange =>
            "Name at least one setting to change, or one to reset."
          case RecoveryError.SetAndReset(settings) =>
            s"${names(settings)} cannot be both set and reset in one call."
          case RecoveryError.Negative(settings) =>
            s"${names(settings)} cannot be negative."
          case RecoveryError.CeilingBelowMaximum(ceiling, maximum) =>
            s"ceilingSeconds would be ${ceiling.getSeconds}, below maximumSeconds at ${maximum
                .getSeconds}: no wait could reach the maximum. Set both."
          case other =>
            other.toString

  private def slugOf(raw: String): IO[String, Slug] = ZIO
    .fromEither(Slug.parse(raw.trim))
    .mapError(LocationCommands.describe)

  val status: McpToolHandlerR[QuillSqlite] = McpTool("get_recovery_policy")
    .description(
      "How a location's portal treats errors: how long its guests wait for a way out of an error in the portal's own code, what shortens or lengthens that wait, and how long a playback whose page never ended stays open. Every value is the one in force, whether the location set it or takes the default; `defaults` names the ones it takes.")
    .readOnly
    .handle: (in: StatusRequest) =>
      for
        slug   <- slugOf(in.slug)
        quill  <- ZIO.service[QuillSqlite]
        report <- Commands.ask(RecoveryPolicyOf(quill), RecoveryPolicyOfQuery(slug))(describe)
      yield viewOf(slug, report)

  val set: McpToolHandlerR[OperatorContext & ContentFlows.ChangeRecoveryPolicy] = McpTool(
    "set_recovery_policy")
    .description(
      "Change how a location's portal treats errors: any of the settings get_recovery_policy reports, each in its own field, and `reset` to return some to their default. Settings not named are kept. Applies to the next guest who asks for a way out and the next playback that starts, with no redeploy; a wait already offered keeps its length. A server error always opens at once and is not affected.")
    .requireScopes(Auth.ContentWrite)
    .handleWithContext: (in: SetRequest, ctx: McpToolContext) =>
      for
        slug      <- slugOf(in.slug)
        reset     <- resetOf(in.reset)
        caller    <- Commands.callerOf(ctx)
        flow      <- ZIO.service[ContentFlows.ChangeRecoveryPolicy]
        overrides <-
          Commands.run(
            Operator(caller, None),
            ChangeRecoveryPolicyCommand(slug, requested(in), reset, Instant.now))(flow)(describe)
      yield viewOf(
        slug,
        RecoveryPolicyReport(
          slug,
          overrides.policy,
          overrides.grace,
          PolicySetting.values.toList.filterNot(overrides.set.contains)))

  val all
      : List[McpToolHandlerR[QuillSqlite & OperatorContext & ContentFlows.ChangeRecoveryPolicy]] =
    List(status, set)
end RecoveryTools
