package whitelabel.captal.core.recovery

import java.time.{Duration, Instant}
import java.util.UUID

import whitelabel.captal.core.application.Phase
import whitelabel.captal.core.user

opaque type ErrorId = UUID
object ErrorId:
  def apply(value: UUID): ErrorId = value
  def generate: ErrorId = UUID.randomUUID()
  def fromString(s: String): Option[ErrorId] = scala.util.Try(UUID.fromString(s)).toOption

  extension (id: ErrorId)
    def value: UUID = id
    def asString: String = id.toString

/** A [[Signature]], hashed: by the repository, since `core` also compiles to JavaScript. */
opaque type ErrorFingerprint = String
object ErrorFingerprint:
  def apply(value: String): ErrorFingerprint = value

  extension (fp: ErrorFingerprint)
    def value: String = fp

/** Where an error was caught. A server error the server saw; a client one the client says it saw.
  */
enum Origin:
  case Server,
    Client

/** One frame of a client stack trace, as the browser printed it. */
final case class Frame(file: String, line: Int, column: Int):
  /** Typed into the console or run from a bookmarklet rather than thrown by the page's code. */
  def fromConsole: Boolean = Frame.ConsoleMarkers.exists(file.contains)

  /** The file without where it was served from: each location serves its own copy of the bundle.
    */
  def fileName: String = file
    .takeWhile(c => c != '?' && c != '#')
    .split('/')
    .lastOption
    .getOrElse("")

object Frame:
  /** How each engine names code that has no file: V8, SpiderMonkey, JavaScriptCore, a bookmarklet.
    */
  val ConsoleMarkers: List[String] = List(
    "<anonymous>",
    "debugger eval code",
    "eval code",
    "global code",
    "javascript:")

  /** Frames kept from one stack: the first is where the error was thrown, the rest only how it got
    * there.
    */
  val MaxFrames: Int = 20

  /** A stack as the engines print it: V8 as `at name (file:line:column)` lines under the message,
    * SpiderMonkey and JavaScriptCore as `name@file:line:column`. The engine's own frames, which
    * name no file (`at Array.map (<anonymous>)`, `map@[native code]`), are dropped. A frame naming
    * neither a file nor native code — JavaScriptCore's `global code` for the console — is kept as
    * its text at 0:0, for [[ConsoleMarkers]] to find.
    */
  def fromStack(stack: String): List[Frame] =
    val lines = stack.linesIterator.map(_.trim).filter(_.nonEmpty).toList
    val frames =
      if lines.exists(_.startsWith("at ")) then
        lines
          .filter(_.startsWith("at "))
          .flatMap(line => located(line.stripPrefix("at ")).filter(_.line > 0))
      else
        lines.flatMap(line => located(line.substring(line.indexOf('@') + 1)))
    frames.take(MaxFrames)

  private val Position = """(.*):(\d+):(\d+)""".r

  private def located(text: String): Option[Frame] =
    val location =
      if text.endsWith(")") && text.contains("(") then
        text.substring(text.lastIndexOf('(') + 1, text.length - 1)
      else
        text
    location match
      case Position(file, line, column) =>
        Some(Frame(file, line.toInt, column.toInt))
      case "" | "[native code]" =>
        None
      case other =>
        Some(Frame(other, 0, 0))
  end located
end Frame

/** A client's account of an error. Nothing in it can be verified; it can only be found inconsistent
  * with what was published, or found again on other devices.
  *
  * @param name
  *   the error's `name` (`TypeError`, `RangeError`, …), which the standard fixes for every engine
  */
final case class ClientReport(
    bundleVersion: String,
    name: String,
    message: String,
    frames: List[Frame]):

  /** What the error is recognized by on other devices: its first frame in the page's own code, or
    * `None` when it has none, since an error with nothing to place it by is never recurrent.
    * Without a manifest, the first frame not from the console.
    */
  def signature(published: Option[Manifest]): Option[Signature] = frames
    .find(f => !f.fromConsole && published.forall(_.owns(f)))
    .map(f => Signature(bundleVersion, name, f.fileName, f.line, f.column))

/** One client error, wherever it happens: what the standard fixes about it and where in the bundle
  * it was thrown. Not the message: each engine words the same error its own way, and versions of
  * one engine reword it. Not the deeper frames, which follow the path that led there.
  */
final case class Signature(
    bundleVersion: String,
    name: String,
    file: String,
    line: Int,
    column: Int)

/** The bundle a location serves: its version and, per file, the length of each line. */
final case class Manifest(version: String, files: Map[String, Vector[Int]]):
  /** Whether a frame names one of this bundle's files. Frames of other origins — an extension, the
    * browser itself — are not ours to judge.
    */
  def owns(frame: Frame): Boolean = files.contains(frame.fileName)

  /** Whether a frame claiming one of our files points inside it. */
  def holds(frame: Frame): Boolean = files
    .get(frame.fileName)
    .exists(lines =>
      frame.line >= 1 && frame.line <= lines.size && frame.column >= 1 &&
        frame.column <= lines(frame.line - 1) + 1)

enum Reason:
  case UnknownVersion,
    ConsoleOrigin,
    OutsideTheBundle

enum Verdict:
  /** Something in it contradicts what the server knows. */
  case Fabricated(reasons: List[Reason])

  /** Nothing contradicts it; recurrent when other devices reported the same error. */
  case Plausible(recurrent: Boolean)

object Verdict:
  /** Other devices that must have reported the same error for it to count as recurrent: one, since
    * the device is the link shortener's, not something a guest can mint.
    */
  val RecurrenceThreshold: Int = 1

  /** @param published
    *   the bundle the location serves, or `None` when it could not be read: a deployment that
    *   failed to publish one must not hold every guest's error against them, so only the console
    *   check runs then.
    */
  def of(report: ClientReport, published: Option[Manifest], otherDevices: Int): Verdict =
    val console = Option.when(report.frames.exists(_.fromConsole))(Reason.ConsoleOrigin).toList
    val bundle = published
      .toList
      .flatMap: manifest =>
        if manifest.version != report.bundleVersion then
          List(Reason.UnknownVersion)
        else
          Option
            .when(report.frames.filter(manifest.owns).exists(f => !manifest.holds(f)))(
              Reason.OutsideTheBundle)
            .toList
    val reasons = console ++ bundle
    if reasons.nonEmpty then
      Fabricated(reasons)
    else
      Plausible(recurrent = otherDevices >= RecurrenceThreshold)
end Verdict

/** What a user has done on one device: the trust they have earned there, by kind of effort. The
  * identification questions are counted apart, and the email apart from them: answering it again
  * and again is what clearing cookies looks like.
  *
  * @param identificationAnswers
  *   profiling and location answers
  */
final case class Activity(
    videosWatched: Int,
    videoAnswers: Int,
    identificationAnswers: Int,
    emailAnswers: Int)
object Activity:
  val Empty: Activity = Activity(0, 0, 0, 0)

/** How far this visit got before it failed. */
enum Progress:
  /** At the welcome or at the identification question. */
  case Started

  /** Identified, at the video. */
  case Identified

  /** The video watched, at its survey. */
  case Watched

object Progress:
  /** The final phases are taken as `Watched`; they have no way out, so it is never asked. */
  def of(phase: Phase): Progress =
    phase match
      case Phase.Welcome | Phase.IdentificationQuestion =>
        Started
      case Phase.AdvertiserVideo =>
        Identified
      case Phase.AdvertiserVideoSurvey | Phase.AdvertiserQuestion | Phase.Ready | Phase.Recovered |
          Phase.Authorized =>
        Watched

  /** The error is in granting access itself, `Recovered` included, which is where a way out already
    * led.
    */
  def isFinal(phase: Phase): Boolean =
    phase == Phase.Ready || phase == Phase.Recovered || phase == Phase.Authorized

final case class Evidence(
    activity: Activity,
    progress: Progress,
    recentRecoveries: Int,
    verdict: Verdict)

/** How long a client error waits before its way out opens: long enough that inventing one is slower
  * than going through the flow, short for whoever has shown, here and now, that they would have.
  *
  * Each piece of evidence divides or multiplies the wait. History counts with diminishing returns,
  * `(1 + n)^-weight` per kind of effort, the heaviest for videos and the lightest for the email.
  * This visit's progress multiplies it by `identifiedFactor` once identified, and by
  * `watchedFactor` too once the video is watched. A fabricated report multiplies it by
  * `fabricatedFactor` and a recurrent one by `recurrentFactor`; the first excludes the second.
  */
final case class WaitPolicy(
    maximum: Duration,
    ceiling: Duration,
    zeroBelow: Duration,
    videoWeight: Double,
    videoAnswerWeight: Double,
    identificationWeight: Double,
    emailWeight: Double,
    identifiedFactor: Double,
    watchedFactor: Double,
    recoveryExponent: Double,
    fabricatedFactor: Double,
    recurrentFactor: Double,
    recoveryWindow: Duration,
    recurrenceWindow: Duration):

  def waitFor(e: Evidence): Duration =
    val history =
      math.pow(1.0 + e.activity.videosWatched, -videoWeight) *
        math.pow(1.0 + e.activity.videoAnswers, -videoAnswerWeight) *
        math.pow(1.0 + e.activity.identificationAnswers, -identificationWeight) *
        math.pow(1.0 + e.activity.emailAnswers, -emailWeight)
    val progress =
      e.progress match
        case Progress.Started =>
          1.0
        case Progress.Identified =>
          identifiedFactor
        case Progress.Watched =>
          identifiedFactor * watchedFactor
    val penalty = math.pow(1.0 + e.recentRecoveries, recoveryExponent)
    val report =
      e.verdict match
        case Verdict.Fabricated(_) =>
          fabricatedFactor
        case Verdict.Plausible(recurrent) =>
          if recurrent then
            recurrentFactor
          else
            1.0
    rounded(
      math.min(
        maximum.getSeconds * history * progress * penalty * report,
        ceiling.getSeconds.toDouble))
  end waitFor

  /** A wait offered before the error was found recurrent, now that it is: `recurrentFactor` of it,
    * still counted from when the error happened, so the guest is not made to start over.
    */
  def recurring(recordedAt: Instant, availableAt: Instant): Instant =
    val offered = Duration.between(recordedAt, availableAt).getSeconds.toDouble
    recordedAt.plus(rounded(offered * recurrentFactor))

  private def rounded(seconds: Double): Duration =
    if seconds < zeroBelow.getSeconds then
      Duration.ZERO
    else
      Duration.ofSeconds(math.round(seconds))
end WaitPolicy

object WaitPolicy:
  val Default: WaitPolicy = WaitPolicy(
    maximum = Duration.ofMinutes(5),
    ceiling = Duration.ofMinutes(30),
    zeroBelow = Duration.ofSeconds(5),
    videoWeight = 1.0,
    videoAnswerWeight = 0.5,
    identificationWeight = 0.25,
    emailWeight = 0.05,
    identifiedFactor = 0.3,
    watchedFactor = 0.5,
    recoveryExponent = 2.0,
    fabricatedFactor = 10.0,
    recurrentFactor = 0.1,
    recoveryWindow = Duration.ofDays(30),
    recurrenceWindow = Duration.ofDays(7)
  )

/** An error the server recorded, and where its way out stands.
  *
  * @param fingerprint
  *   a client error's, when it had a frame of the bundle's own
  * @param verdict
  *   a client error's
  */
final case class ErrorRecord(
    errorId: ErrorId,
    sessionId: Option[user.SessionId],
    origin: Origin,
    recordedAt: Instant,
    fingerprint: Option[ErrorFingerprint],
    verdict: Option[Verdict],
    availableAt: Option[Instant],
    redeemedAt: Option[Instant])

enum Event:
  case ErrorRecorded(
      errorId: ErrorId,
      sessionId: user.SessionId,
      deviceId: user.DeviceId,
      report: ClientReport,
      fingerprint: Option[ErrorFingerprint],
      verdict: Verdict,
      occurredAt: Instant)

  /** The session may leave through this error from `availableAt`. */
  case RecoveryOffered(
      errorId: ErrorId,
      sessionId: user.SessionId,
      availableAt: Instant,
      occurredAt: Instant)

  /** Another device reported the same error since it was offered: its wait shortens to
    * `availableAt`, and the error is recurrent from now on.
    */
  case RecoveryShortened(
      errorId: ErrorId,
      sessionId: user.SessionId,
      availableAt: Instant,
      occurredAt: Instant)

  /** The session left through it: access is granted as at the end of the flow. */
  case RecoveryRedeemed(errorId: ErrorId, sessionId: user.SessionId, occurredAt: Instant)
end Event

enum Error:
  /** Not an error of this session, or no error at all. */
  case UnknownError(errorId: ErrorId)
  case AlreadyRedeemed(errorId: ErrorId)
  case NotYet(availableAt: Instant)
