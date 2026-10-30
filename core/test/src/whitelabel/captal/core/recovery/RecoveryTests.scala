package whitelabel.captal.core.recovery

import java.time.{Duration, Instant}

import cats.Id
import utest.*
import whitelabel.captal.core.application.commands.*
import whitelabel.captal.core.application.{Error as AppError, Event as AppEvent, Phase}
import whitelabel.captal.core.infrastructure.{RecoveryRepository, SessionData}
import whitelabel.captal.core.{Op, user}

object RecoveryTests extends TestSuite:
  private val Now = Instant.parse("2026-10-30T12:00:00Z")
  private val Device = user.DeviceId.generate
  private val Session = user.SessionId.generate

  private val Bundle = Manifest(
    "1.4.0",
    Map("main.js" -> Vector(120, 40000), "chunk-a.js" -> Vector(900)))

  private def ours(line: Int, column: Int) = Frame("https://cdn.test/cafe/main.js", line, column)

  private def report(frames: Frame*) = ClientReport(
    "1.4.0",
    "TypeError",
    "Cannot read properties of undefined (reading 'x')",
    frames.toList)

  private def session(phase: Phase, userId: Option[user.Id] = None) = SessionData(
    Session,
    Device,
    userId,
    "es",
    phase,
    None,
    None)

  private def repo(
      records: Map[ErrorId, ErrorRecord] = Map.empty,
      published: Option[Manifest] = Some(Bundle),
      otherDevices: Int = 0,
      recent: Int = 0,
      done: Activity = Activity.Empty,
      asked: collection.mutable.Buffer[ErrorFingerprint] = collection.mutable.Buffer.empty)
      : RecoveryRepository[Id] =
    new RecoveryRepository[Id]:
      def find(errorId: ErrorId) = records.get(errorId)
      def fingerprintOf(signature: Signature) = ErrorFingerprint(signature.toString)
      def publishedBundle() = published
      def devicesReporting(fp: ErrorFingerprint, excluding: user.DeviceId, since: Instant) =
        asked += fp
        otherDevices
      def recoveriesSince(deviceId: user.DeviceId, since: Instant) = recent
      def activity(userId: user.Id, deviceId: user.DeviceId) = done

  private def offered(outcome: Either[?, (List[AppEvent], RecoveryOutcome)]) =
    outcome match
      case Right((_, RecoveryOutcome.Offered(_, availableAt, _))) =>
        Some(Duration.between(Now, availableAt))
      case _ =>
        None

  val tests = Tests:

    test("Verdict"):
      test("a frame from the console is fabricated, whatever else it says"):
        val verdict = Verdict.of(report(Frame("<anonymous>", 1, 1), ours(2, 10)), Some(Bundle), 5)
        assert(verdict == Verdict.Fabricated(List(Reason.ConsoleOrigin)))
      test("a version that is not the one published is fabricated"):
        val verdict = Verdict.of(report(ours(2, 10)).copy(bundleVersion = "0.9.0"), Some(Bundle), 0)
        assert(verdict == Verdict.Fabricated(List(Reason.UnknownVersion)))
      test("a position outside our file is fabricated"):
        assert(
          Verdict.of(report(ours(3, 1)), Some(Bundle), 0) ==
            Verdict.Fabricated(List(Reason.OutsideTheBundle)),
          Verdict.of(report(ours(1, 500)), Some(Bundle), 0) ==
            Verdict.Fabricated(List(Reason.OutsideTheBundle))
        )
      test("frames of other origins are not ours to judge"):
        val extension = Frame("chrome-extension://abc/content.js", 9999, 9999)
        assert(
          Verdict.of(report(extension, ours(2, 10)), Some(Bundle), 0) == Verdict.Plausible(false))
      test("with no manifest to read, only the console is judged"):
        assert(
          Verdict.of(report(ours(99, 99)), None, 0) == Verdict.Plausible(false),
          Verdict.of(report(Frame("<anonymous>", 1, 1)), None, 0) ==
            Verdict.Fabricated(List(Reason.ConsoleOrigin))
        )
      test("seen on another device, it is recurrent"):
        assert(
          Verdict.of(report(ours(2, 10)), Some(Bundle), 0) == Verdict.Plausible(false),
          Verdict.of(report(ours(2, 10)), Some(Bundle), 1) == Verdict.Plausible(true))

    test("Frame.fromStack"):
      test("V8: the frames under the message, without the engine's own"):
        val stack =
          """TypeError: Cannot read properties of undefined (reading 'x')
            |    at Kd (https://cdn.test/cafe/main.js:1:4120)
            |    at Array.map (<anonymous>)
            |    at https://cdn.test/cafe/chunk-a.js:1:77
            |    at async Promise.all (index 0)""".stripMargin
        assert(
          Frame.fromStack(stack) ==
            List(
              Frame("https://cdn.test/cafe/main.js", 1, 4120),
              Frame("https://cdn.test/cafe/chunk-a.js", 1, 77)))
      test("SpiderMonkey and JavaScriptCore: one frame a line, natives dropped"):
        val stack =
          """Kd@https://cdn.test/cafe/main.js:1:4120
            |map@[native code]
            |@https://cdn.test/cafe/chunk-a.js:1:77
            |""".stripMargin
        assert(
          Frame.fromStack(stack) ==
            List(
              Frame("https://cdn.test/cafe/main.js", 1, 4120),
              Frame("https://cdn.test/cafe/chunk-a.js", 1, 77)))
      test("each engine's console is found as the console"):
        val chrome = "Error: x\n    at <anonymous>:1:7"
        val firefox = "@debugger eval code:1:7\n"
        val safari = "global code\nevaluateWithScopeExtension@[native code]\n@\n_wrapCall@"
        assert(
          Frame.fromStack(chrome).exists(_.fromConsole),
          Frame.fromStack(firefox).exists(_.fromConsole),
          Frame.fromStack(safari).exists(_.fromConsole))
      test("no more than MaxFrames"):
        val deep = (1 to 50).map(i => s"f$i@https://cdn.test/cafe/main.js:1:$i").mkString("\n")
        assert(Frame.fromStack(deep).size == Frame.MaxFrames)
      test("no stack, no frames"):
        assert(Frame.fromStack("").isEmpty)

    test("Signature"):
      test("the same error in each engine's words is one error"):
        val chrome = report(ours(1, 4120))
        val safari = chrome.copy(message = "undefined is not an object (evaluating 'a.x')")
        assert(chrome.signature(Some(Bundle)) == safari.signature(Some(Bundle)))
      test("its name tells two errors at one place apart"):
        val typeError = report(ours(1, 4120))
        val rangeError = typeError.copy(name = "RangeError")
        assert(typeError.signature(Some(Bundle)) != rangeError.signature(Some(Bundle)))
      test("its place is the first frame of ours, whatever led there"):
        val extension = Frame("chrome-extension://abc/content.js", 7, 7)
        val direct = report(ours(1, 4120), Frame("https://cdn.test/cafe/main.js", 1, 900))
        val throughExtension = report(extension, ours(1, 4120), ours(1, 77))
        assert(
          direct.signature(Some(Bundle)) ==
            Some(Signature("1.4.0", "TypeError", "main.js", 1, 4120)),
          throughExtension.signature(Some(Bundle)) == direct.signature(Some(Bundle))
        )
      test("one bundle served from two locations is one place"):
        val here = report(ours(1, 4120))
        val there = report(Frame("https://cdn.test/plaza-norte/main.js?v=3", 1, 4120))
        assert(here.signature(Some(Bundle)) == there.signature(Some(Bundle)))
      test("without a frame of ours there is nothing to recognize it by"):
        assert(
          report().signature(Some(Bundle)).isEmpty,
          report(Frame("<anonymous>", 1, 1)).signature(None).isEmpty,
          report(Frame("chrome-extension://abc/content.js", 7, 7)).signature(Some(Bundle)).isEmpty
        )

    test("WaitPolicy"):
      val policy = WaitPolicy.Default
      val plausible = Verdict.Plausible(false)
      val recurrent = Verdict.Plausible(true)
      val fabricated = Verdict.Fabricated(List(Reason.ConsoleOrigin))
      def waitOf(
          activity: Activity = Activity.Empty,
          progress: Progress = Progress.Started,
          recent: Int = 0,
          verdict: Verdict = plausible) =
        policy.waitFor(Evidence(activity, progress, recent, verdict)).getSeconds

      /** Four videos and their four answers, five profiling answers, the email once. */
      val regular = Activity(
        videosWatched = 4,
        videoAnswers = 4,
        identificationAnswers = 5,
        emailAnswers = 1)

      test("a new guest at the welcome, plausible, waits the maximum"):
        assert(waitOf() == 300)
      test("this visit: identified, a minute and a half; the video watched, three quarters"):
        assert(
          waitOf(progress = Progress.Identified) == 90,
          waitOf(progress = Progress.Watched) == 45)
      test("fabricated stops at the ceiling, from nothing"):
        assert(waitOf(verdict = fabricated) == 1800)
      test("fabricated with history is ten times as long, short of the ceiling"):
        assert(waitOf(regular) == 17, waitOf(regular, verdict = fabricated) == 166)
      test("the email alone is worth almost nothing"):
        assert(waitOf(Activity(0, 0, 0, emailAnswers = 1)) == 290)
      test("the more identification answers, the shorter, with diminishing returns"):
        assert(
          waitOf(Activity(0, 0, identificationAnswers = 1, emailAnswers = 1)) == 244,
          waitOf(Activity(0, 0, identificationAnswers = 8, emailAnswers = 1)) == 167)
      test("a video counts more than its answer, its answer more than an identification answer"):
        val video = waitOf(Activity(videosWatched = 1, 0, 0, 0))
        val videoAnswer = waitOf(Activity(0, videoAnswers = 1, 0, 0))
        val identification = waitOf(Activity(0, 0, identificationAnswers = 1, 0))
        val email = waitOf(Activity(0, 0, 0, emailAnswers = 1))
        assert(video < videoAnswer, videoAnswer < identification, identification < email)
      test("recurrent, a tenth; below five seconds, nothing"):
        assert(
          waitOf(progress = Progress.Identified, verdict = recurrent) == 9,
          waitOf(progress = Progress.Watched, verdict = recurrent) == 0,
          waitOf(regular, verdict = recurrent) == 0
        )
      test("ways out taken lately lengthen it, up to the ceiling"):
        assert(
          waitOf(recent = 1) == 1200,
          waitOf(recent = 3) == 1800,
          waitOf(regular, recent = 2) == 149)
      test("a wait found recurrent later is a tenth of itself, from when the error happened"):
        assert(
          policy.recurring(Now, Now.plusSeconds(300)) == Now.plusSeconds(30),
          policy.recurring(Now, Now.plusSeconds(45)) == Now)

    test("RequestRecoveryHandler"):
      test("a server error of this session opens at once"):
        val id = ErrorId.generate
        val record = ErrorRecord(id, Some(Session), Origin.Server, Now, None, None, None, None)
        val handler = RequestRecoveryHandler.withSession(
          repo(records = Map(id -> record)),
          WaitPolicy.Default,
          session(Phase.AdvertiserVideo))
        val result = Op.run(handler.handle(RequestRecoveryCommand(RecoveryCause.Server(id), Now)))
        assert(offered(result).contains(Duration.ZERO))
      test("another session's server error is not this one's to leave through"):
        val id = ErrorId.generate
        val record = ErrorRecord(
          id,
          Some(user.SessionId.generate),
          Origin.Server,
          Now,
          None,
          None,
          None,
          None)
        val handler = RequestRecoveryHandler.withSession(
          repo(records = Map(id -> record)),
          WaitPolicy.Default,
          session(Phase.Welcome))
        val result = Op.run(handler.handle(RequestRecoveryCommand(RecoveryCause.Server(id), Now)))
        assert(
          result
            .left
            .exists(
              _.exists {
                case AppError.Recovery(errors) =>
                  errors.head == Error.UnknownError(id)
                case _ =>
                  false
              }))
      test("a client error is recorded and waits by its evidence"):
        val handler = RequestRecoveryHandler.withSession(
          repo(),
          WaitPolicy.Default,
          session(Phase.AdvertiserVideo))
        val result = Op.run(
          handler.handle(RequestRecoveryCommand(RecoveryCause.Client(report(ours(2, 10))), Now)))
        val events = result.toOption.get._1
        assert(
          offered(result).contains(Duration.ofSeconds(90)),
          events.exists {
            case AppEvent.Recovery(_: Event.ErrorRecorded) =>
              true
            case _ =>
              false
          })
      test("an error with nothing to place it by is never counted as recurrent"):
        val asked = collection.mutable.Buffer.empty[ErrorFingerprint]
        val handler = RequestRecoveryHandler.withSession(
          repo(otherDevices = 9, asked = asked),
          WaitPolicy.Default,
          session(Phase.Welcome))
        val result = Op.run(
          handler.handle(RequestRecoveryCommand(RecoveryCause.Client(report()), Now)))
        assert(
          offered(result).contains(Duration.ofMinutes(5)),
          asked.isEmpty,
          result
            .toOption
            .get
            ._1
            .exists {
              case AppEvent.Recovery(e: Event.ErrorRecorded) =>
                e.fingerprint.isEmpty
              case _ =>
                false
            }
        )
      test("in the final phase there is no way out, only a code"):
        val handler = RequestRecoveryHandler.withSession(
          repo(),
          WaitPolicy.Default,
          session(Phase.Ready))
        val result = Op.run(
          handler.handle(RequestRecoveryCommand(RecoveryCause.Client(report(ours(2, 10))), Now)))
        assert(result.toOption.get._2.isInstanceOf[RecoveryOutcome.Unrecoverable])

    test("CheckRecoveryHandler"):
      val id = ErrorId.generate
      val fp = ErrorFingerprint("fp")
      val waiting = ErrorRecord(
        id,
        Some(Session),
        Origin.Client,
        Now,
        Some(fp),
        Some(Verdict.Plausible(false)),
        Some(Now.plusSeconds(300)),
        None)
      def checking(
          record: ErrorRecord,
          others: Int = 0,
          asked: collection.mutable.Buffer[ErrorFingerprint] = collection.mutable.Buffer.empty) = Op
        .run(
          CheckRecoveryHandler
            .withSession(
              repo(records = Map(id -> record), otherDevices = others, asked = asked),
              WaitPolicy.Default,
              session(Phase.Welcome))
            .handle(CheckRecoveryCommand(id, Now.plusSeconds(10))))
      test("reported by another device since, the wait shortens to a tenth, and says so"):
        assert(
          checking(waiting, others = 1).toOption.get == (
            List(
              AppEvent.Recovery(
                Event.RecoveryShortened(id, Session, Now.plusSeconds(30), Now.plusSeconds(10)))),
            RecoveryOutcome.Offered(id, Now.plusSeconds(30), recurrent = true)))
      test("reported by nobody else, it stands as offered"):
        assert(
          checking(waiting).toOption.get ==
            (Nil, RecoveryOutcome.Offered(id, Now.plusSeconds(300), recurrent = false)))
      test("a fabricated report is never shortened"):
        val asked = collection.mutable.Buffer.empty[ErrorFingerprint]
        val fabricated = waiting.copy(verdict = Some(
          Verdict.Fabricated(List(Reason.OutsideTheBundle))))
        assert(
          checking(fabricated, others = 5, asked = asked).toOption.get ==
            (Nil, RecoveryOutcome.Offered(id, Now.plusSeconds(300), recurrent = false)),
          asked.isEmpty)
      test("one already recurrent stays as it is"):
        val recurrent = waiting.copy(
          verdict = Some(Verdict.Plausible(true)),
          availableAt = Some(Now.plusSeconds(30)))
        assert(
          checking(recurrent, others = 5).toOption.get ==
            (Nil, RecoveryOutcome.Offered(id, Now.plusSeconds(30), recurrent = true)))
      test("not this session's, or already taken, is refused"):
        assert(
          checking(waiting.copy(sessionId = Some(user.SessionId.generate))).isLeft,
          checking(waiting.copy(redeemedAt = Some(Now))).isLeft)

    test("RedeemRecoveryHandler"):
      val id = ErrorId.generate
      def redeeming(record: ErrorRecord, at: Instant, others: Int = 0) = Op.run(
        RedeemRecoveryHandler
          .withSession(
            repo(records = Map(id -> record), otherDevices = others),
            WaitPolicy.Default,
            session(Phase.Welcome))
          .handle(RedeemRecoveryCommand(id, at)))
      val offeredAt = ErrorRecord(
        id,
        Some(Session),
        Origin.Client,
        Now,
        Some(ErrorFingerprint("fp")),
        Some(Verdict.Plausible(false)),
        Some(Now.plusSeconds(60)),
        None)
      test("once the error is recurrent, its shortened wait is what counts, page asked or not"):
        val longer = offeredAt.copy(availableAt = Some(Now.plusSeconds(300)))
        assert(
          redeeming(longer, Now.plusSeconds(31)).isLeft,
          redeeming(longer, Now.plusSeconds(31), others = 1).toOption.get._1 ==
            List(
              AppEvent.Recovery(
                Event.RecoveryShortened(id, Session, Now.plusSeconds(30), Now.plusSeconds(31))),
              AppEvent.Recovery(Event.RecoveryRedeemed(id, Session, Now.plusSeconds(31)))
            )
        )
      test("before its wait is over, it is refused with when"):
        assert(
          redeeming(offeredAt, Now)
            .left
            .exists(
              _.exists {
                case AppError.Recovery(errors) =>
                  errors.head == Error.NotYet(Now.plusSeconds(60))
                case _ =>
                  false
              }))
      test("after it, the session leaves"):
        assert(
          redeeming(offeredAt, Now.plusSeconds(61)).toOption.get._1 ==
            List(AppEvent.Recovery(Event.RecoveryRedeemed(id, Session, Now.plusSeconds(61)))))
      test("once only"):
        val used = offeredAt.copy(redeemedAt = Some(Now))
        assert(redeeming(used, Now.plusSeconds(61)).isLeft)
end RecoveryTests
