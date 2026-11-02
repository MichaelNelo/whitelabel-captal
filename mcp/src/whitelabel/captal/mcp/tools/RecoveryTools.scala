package whitelabel.captal.mcp.tools

import java.time.Instant

import com.jamesward.ziohttp.mcp.*
import io.getquill.*
import whitelabel.captal.mcp.Writes
import whitelabel.captal.mcp.access.Auth
import whitelabel.captal.mcp.protocol.{McpSchema, ToolSchema}
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.{LocationRecovery, LocationRecoveryRow, LocationRow}
import zio.*
import zio.schema.annotation.description
import zio.schema.{DeriveSchema, Schema}

import McpSchema.given
import ToolSchema.readOnly

/** A location's recovery settings: how long its guests wait for a way out of an error in the
  * portal's own code, and how long a playback its page never ended stays open. Stored per location
  * in [[LocationRecovery]], read by the portal and the backoffice as they need them, so a change
  * applies to the next guest with no redeploy.
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

  /** Each setting by its name in these tools: what a row holds for it, and the row without it. */
  final private case class Setting(
      name: String,
      of: LocationRecoveryRow => Option[Any],
      cleared: LocationRecoveryRow => LocationRecoveryRow)

  private val Settings: List[Setting] = List(
    Setting("maximumSeconds", _.maximumSeconds, _.copy(maximumSeconds = None)),
    Setting("ceilingSeconds", _.ceilingSeconds, _.copy(ceilingSeconds = None)),
    Setting("zeroBelowSeconds", _.zeroBelowSeconds, _.copy(zeroBelowSeconds = None)),
    Setting("videoWeight", _.videoWeight, _.copy(videoWeight = None)),
    Setting("videoAnswerWeight", _.videoAnswerWeight, _.copy(videoAnswerWeight = None)),
    Setting("identificationWeight", _.identificationWeight, _.copy(identificationWeight = None)),
    Setting("emailWeight", _.emailWeight, _.copy(emailWeight = None)),
    Setting("identifiedFactor", _.identifiedFactor, _.copy(identifiedFactor = None)),
    Setting("watchedFactor", _.watchedFactor, _.copy(watchedFactor = None)),
    Setting("recoveryExponent", _.recoveryExponent, _.copy(recoveryExponent = None)),
    Setting("fabricatedFactor", _.fabricatedFactor, _.copy(fabricatedFactor = None)),
    Setting("recurrentFactor", _.recurrentFactor, _.copy(recurrentFactor = None)),
    Setting("recoveryWindowSeconds", _.recoveryWindowSeconds, _.copy(recoveryWindowSeconds = None)),
    Setting(
      "recurrenceWindowSeconds",
      _.recurrenceWindowSeconds,
      _.copy(recurrenceWindowSeconds = None)),
    Setting("playbackGraceSeconds", _.playbackGraceSeconds, _.copy(playbackGraceSeconds = None))
  )

  private def requested(locationId: String, in: SetRequest, at: String): LocationRecoveryRow =
    LocationRecoveryRow(
      locationId = locationId,
      maximumSeconds = in.maximumSeconds,
      ceilingSeconds = in.ceilingSeconds,
      zeroBelowSeconds = in.zeroBelowSeconds,
      videoWeight = in.videoWeight,
      videoAnswerWeight = in.videoAnswerWeight,
      identificationWeight = in.identificationWeight,
      emailWeight = in.emailWeight,
      identifiedFactor = in.identifiedFactor,
      watchedFactor = in.watchedFactor,
      recoveryExponent = in.recoveryExponent,
      fabricatedFactor = in.fabricatedFactor,
      recurrentFactor = in.recurrentFactor,
      recoveryWindowSeconds = in.recoveryWindowSeconds,
      recurrenceWindowSeconds = in.recurrenceWindowSeconds,
      playbackGraceSeconds = in.playbackGraceSeconds,
      updatedAt = at
    )

  /** `stored`, with what `change` sets replacing it and the rest kept. */
  private def merged(
      stored: LocationRecoveryRow,
      change: LocationRecoveryRow): LocationRecoveryRow = LocationRecoveryRow(
    locationId = stored.locationId,
    maximumSeconds = change.maximumSeconds.orElse(stored.maximumSeconds),
    ceilingSeconds = change.ceilingSeconds.orElse(stored.ceilingSeconds),
    zeroBelowSeconds = change.zeroBelowSeconds.orElse(stored.zeroBelowSeconds),
    videoWeight = change.videoWeight.orElse(stored.videoWeight),
    videoAnswerWeight = change.videoAnswerWeight.orElse(stored.videoAnswerWeight),
    identificationWeight = change.identificationWeight.orElse(stored.identificationWeight),
    emailWeight = change.emailWeight.orElse(stored.emailWeight),
    identifiedFactor = change.identifiedFactor.orElse(stored.identifiedFactor),
    watchedFactor = change.watchedFactor.orElse(stored.watchedFactor),
    recoveryExponent = change.recoveryExponent.orElse(stored.recoveryExponent),
    fabricatedFactor = change.fabricatedFactor.orElse(stored.fabricatedFactor),
    recurrentFactor = change.recurrentFactor.orElse(stored.recurrentFactor),
    recoveryWindowSeconds = change.recoveryWindowSeconds.orElse(stored.recoveryWindowSeconds),
    recurrenceWindowSeconds = change.recurrenceWindowSeconds.orElse(stored.recurrenceWindowSeconds),
    playbackGraceSeconds = change.playbackGraceSeconds.orElse(stored.playbackGraceSeconds),
    updatedAt = change.updatedAt
  )

  private def viewOf(slug: String, row: Option[LocationRecoveryRow]): PolicyView =
    val policy = LocationRecovery.waitPolicy(row)
    PolicyView(
      slug = slug,
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
      playbackGraceSeconds = LocationRecovery.grace(row).getSeconds,
      defaults = Settings.filter(s => row.forall(s.of(_).isEmpty)).map(_.name)
    )
  end viewOf

  /** What is wrong with a change, before anything is written. Every setting is a length, a weight
    * or a factor, so none is negative; and the ceiling caps the maximum, so it is not below it.
    */
  private def problems(
      change: LocationRecoveryRow,
      reset: List[String],
      result: PolicyView): List[String] =
    val names = Settings.map(_.name)
    val unknown = reset.filterNot(names.contains)
    val both = Settings.filter(s => s.of(change).isDefined && reset.contains(s.name))
    val negative = Settings.filter: s =>
      s.of(change) match
        case Some(n: Int) =>
          n < 0
        case Some(d: Double) =>
          d < 0
        case _ =>
          false
    List(
      Option.when(unknown.nonEmpty)(
        s"No setting is named ${unknown.mkString(", ")}. They are: ${names.mkString(", ")}."),
      Option.when(both.nonEmpty)(
        s"${both.map(_.name).mkString(", ")} cannot be both set and reset in one call."),
      Option.when(negative.nonEmpty)(s"${negative.map(_.name).mkString(", ")} cannot be negative."),
      Option.when(result.ceilingSeconds < result.maximumSeconds)(
        s"ceilingSeconds would be ${result.ceilingSeconds}, below maximumSeconds at ${result
            .maximumSeconds}: no wait could reach the maximum. Set both.")
    ).flatten
  end problems

  private inline def bySlugQuery = quote: (slugParam: String) =>
    query[LocationRow].filter(_.slug == slugParam).take(1)

  private def locationOf(slug: String): ZIO[QuillSqlite, String, LocationRow] = ZIO.serviceWithZIO[
    QuillSqlite]: quill =>
    import quill.*
    run(bySlugQuery(lift(slug)))
      .orDie
      .map(_.headOption)
      .someOrFail(s"No location with slug '$slug'. Call list_locations to see what exists.")

  val status: McpToolHandlerR[QuillSqlite] = McpTool("get_recovery_policy")
    .description(
      "How a location's portal treats errors: how long its guests wait for a way out of an error in the portal's own code, what shortens or lengthens that wait, and how long a playback whose page never ended stays open. Every value is the one in force, whether the location set it or takes the default; `defaults` names the ones it takes.")
    .readOnly
    .handle: (in: StatusRequest) =>
      ZIO.serviceWithZIO[QuillSqlite]: quill =>
        for
          location <- locationOf(in.slug.trim)
          row      <- LocationRecovery.find(quill, location.id).orDie
        yield viewOf(location.slug, row)

  val set: McpToolHandlerR[QuillSqlite] = McpTool("set_recovery_policy")
    .description(
      "Change how a location's portal treats errors: any of the settings get_recovery_policy reports, each in its own field, and `reset` to return some to their default. Settings not named are kept. Applies to the next guest who asks for a way out and the next playback that starts, with no redeploy; a wait already offered keeps its length. A server error always opens at once and is not affected.")
    .requireScopes(Auth.ContentWrite)
    .handle: (in: SetRequest) =>
      ZIO.serviceWithZIO[QuillSqlite]: quill =>
        for
          location <- locationOf(in.slug.trim)
          stored   <- LocationRecovery.find(quill, location.id).orDie
          now    = Instant.now.toString
          change = requested(location.id, in, now)
          _ <-
            ZIO.when(Settings.forall(_.of(change).isEmpty) && in.reset.isEmpty)(
              ZIO.fail("Name at least one setting to change, or one to reset."))
          base = stored.getOrElse(LocationRecoveryRow(location.id, updatedAt = now))
          next =
            Settings
              .filter(s => in.reset.contains(s.name))
              .foldLeft(merged(base, change))((row, setting) => setting.cleared(row))
          result = viewOf(location.slug, Some(next))
          _ <-
            problems(change, in.reset, result) match
              case Nil =>
                ZIO.unit
              case found =>
                ZIO.fail(found.mkString(" "))
          affected <- LocationRecovery.save(quill, next).orDie
          _ <- Writes.expectOne(affected, s"Saving the recovery settings of '${location.slug}'")
        yield result

  val all: List[McpToolHandlerR[QuillSqlite]] = List(status, set)
end RecoveryTools
