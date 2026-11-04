package whitelabel.captal.api.suites

import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder
import io.circe.parser.decode
import io.getquill.*
import whitelabel.captal.api.TestHelpers.*
import whitelabel.captal.api.wire.given
import whitelabel.captal.api.session.CurrentLocation
import whitelabel.captal.api.{
  ApiErrors,
  Flows,
  RecoveryFeed,
  TestFixtures,
  TestUnifiClient,
  WireCheck
}
import whitelabel.captal.core.application.Phase
import whitelabel.captal.core.recovery.{Activity, ErrorFingerprint, WaitPolicy}
import whitelabel.captal.core.survey.question
import whitelabel.captal.core.user
import whitelabel.captal.messages.{ApiError, RecoveryOffer, StatusResponse}
import whitelabel.captal.platform.repositories.RecoveryRepositoryQuill
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.{
  BundleManifest,
  LocationRecovery,
  LocationRecoveryRow,
  LocationRow
}
import whitelabel.captal.platform.session.{JobRunner, SessionContext, SessionService}
import zio.schema.Schema
import zio.test.*
import zio.{ZIO, durationInt}

/** A guest's way out of an error, and what it costs them. */
object RecoverySuite:
  private def offerOf(body: String) = decode[RecoveryOffer](body).toOption
  private def errorOf(body: String) = decode[ApiError](body).toOption

  private val Now = java.time.Instant.parse("2026-10-31T12:00:00Z")

  /** A response whose two derivations disagree, as a change to one of them would leave them: the
    * server writes a bare string, the browser expects an object.
    */
  final private case class Drifted(value: String)
  private given Schema[Drifted] = Schema[String].transform(Drifted(_), _.value)
  private given Decoder[Drifted] = deriveDecoder

  val suite: Spec[TestEnv, Throwable] =
    zio
      .test
      .suite("Recovery")(
        test("a defect is recorded against the session and answered with its id"):
          for
            backend <- testBackend
            cookie  <- createSession(backend)
            session <-
              ZIO
                .serviceWithZIO[SessionService](_.findById(user.SessionId.unsafe(cookie)))
                .someOrFailException
            _      <- SessionContext.set(session)
            answer <- ApiErrors.toApiError(new IllegalStateException("broken"))
            errors <- TestFixtures.getErrors
          yield assertTrue(
            answer match
              case ApiError.InternalError("broken", Some(id)) =>
                errors.exists(e =>
                  e.id == id && e.origin == "server" && e.sessionId.contains(session.sessionId))
              case _ =>
                false)
        ,
        test("a response the browser could not read is recorded, and answered with its id"):
          for
            backend <- testBackend
            cookie  <- createSession(backend)
            session <-
              ZIO
                .serviceWithZIO[SessionService](_.findById(user.SessionId.unsafe(cookie)))
                .someOrFailException
            _       <- SessionContext.set(session)
            fine    <- WireCheck.readable(StatusResponse(Phase.Welcome, "es")).either
            drifted <- WireCheck.readable(Drifted("x")).either
            errors  <- TestFixtures.getErrors
          yield assertTrue(
            fine.isRight,
            drifted match
              case Left(ApiError.InternalError(_, Some(id))) =>
                errors.exists(e =>
                  e.id == id && e.origin == "server" && e.sessionId.contains(session.sessionId))
              case _ =>
                false
          )
        ,
        test("a server error opens at once, and leaving through it leaves finish to grant access"):
          for
            _           <- TestUnifiClient.enable
            backend     <- testBackend
            cookie      <- createSession(backend)
            errorId     <- TestFixtures.recordServerError(user.SessionId.unsafe(cookie))
            offer       <- postRecovery(backend, cookie, s"""{"serverErrorId":"$errorId"}""")
            redeem      <- postRedeem(backend, cookie, errorId)
            afterRedeem <- TestUnifiClient.calls
            again       <- postRedeem(backend, cookie, errorId)
            finish      <- postFinish(backend, cookie)
            errors      <- TestFixtures.getErrors
          yield assertTrue(
            offerOf(offer.body).exists(o => o.errorId == errorId && o.waitSeconds == 0),
            // The redeem moves the phase and nothing else: authorizing is finish's alone.
            decode[StatusResponse](redeem.body).toOption.exists(_.phase == Phase.Recovered),
            afterRedeem.isEmpty,
            errorOf(again.body).contains(ApiError.RecoveryAlreadyUsed(errorId)),
            // No user: the guest left before giving an email, and finish grants access all the same.
            decode[StatusResponse](finish.body).toOption.exists(_.phase == Phase.Authorized),
            errors.exists(e => e.id == errorId && e.redeemedAt.isDefined)
          )
        ,
        test(
          "a server error injected through the dev endpoints is a recorded one, and opens at once"):
          for
            backend <- testBackend
            cookie  <- createSession(backend)
            fault   <- postFault(backend, cookie, "server")
            errorId = errorOf(fault.body).collect:
              case ApiError.InternalError(_, Some(id)) =>
                id
            offer <- postRecovery(
              backend,
              cookie,
              s"""{"serverErrorId":"${errorId.getOrElse("")}"}""")
          yield assertTrue(
            errorId.isDefined,
            offerOf(offer.body).exists(o => errorId.contains(o.errorId) && o.waitSeconds == 0))
        ,
        test("a client fault fails nothing on the server, only answers the session"):
          for
            backend <- testBackend
            cookie  <- createSession(backend)
            fault   <- postFault(backend, cookie, "client")
            unknown <- postFault(backend, cookie, "nothing")
            errors  <- TestFixtures.getErrors
          yield assertTrue(
            // The session's own phase: past the welcome, since `createSession` accepted the terms.
            decode[StatusResponse](fault.body)
              .toOption
              .exists(_.phase == Phase.IdentificationQuestion),
            errorOf(unknown.body).exists:
              case ApiError.InvalidRecoveryRequest(_) =>
                true
              case _ =>
                false
            ,
            errors.isEmpty
          )
        ,
        test("an access granted to a session with no user is revoked when it expires"):
          ZIO.scoped:
            for
              _ <- TestUnifiClient.enable
              // Started here, as `AuthorizedSuite` does: the test configuration's access expires
              // at once.
              runner  <- ZIO.service[JobRunner]
              _       <- runner.start
              backend <- testBackend
              cookie  <- createSession(backend)
              sessionId = user.SessionId.unsafe(cookie)
              errorId <- TestFixtures.recordServerError(sessionId)
              _       <- postRecovery(backend, cookie, s"""{"serverErrorId":"$errorId"}""")
              _       <- postRedeem(backend, cookie, errorId)
              _       <- postFinish(backend, cookie)
              expired <- awaitWithLiveClock:
                TestFixtures
                  .queryDbState
                  .map(_.sessions.find(_.id == sessionId).exists(_.phase == Phase.Welcome))
              calls <- TestUnifiClient.calls
              log   <- TestFixtures.queryEventLog
            yield assertTrue(
              expired,
              calls.exists(_.isInstanceOf[TestUnifiClient.Call.Authorize]),
              calls.exists(_.isInstanceOf[TestUnifiClient.Call.Revoke]),
              log.exists(_.eventType == "access.expired")
            )
        ,
        test("a client error at Welcome waits the maximum, and not a second less"):
          for
            backend <- testBackend
            cookie  <- createSession(backend)
            offer   <- postRecovery(backend, cookie, clientReport())
            parsed = offerOf(offer.body)
            early  <- postRedeem(backend, cookie, parsed.get.errorId)
            _      <- TestClock.adjust(5.minutes)
            onTime <- postRedeem(backend, cookie, parsed.get.errorId)
            errors <- TestFixtures.getErrors
          yield assertTrue(
            parsed.exists(_.waitSeconds == 300),
            errorOf(early.body).exists(_.isInstanceOf[ApiError.RecoveryNotYet]),
            decode[StatusResponse](onTime.body).toOption.exists(_.phase == Phase.Recovered),
            errors.exists(e => e.origin == "client" && e.verdict.contains("plausible"))
          )
        ,
        test("the wait policy is the served location's, with the default for what it leaves out"):
          for
            quill <- ZIO.service[QuillSqlite]
            _     <- insertLocation(quill, "recovery-short")
            _     <- insertLocation(quill, "recovery-bare")
            _     <- LocationRecovery.save(
              quill,
              LocationRecoveryRow(
                "recovery-short",
                maximumSeconds = Some(60),
                recurrentFactor = Some(0.5),
                updatedAt = Now.toString))
            short <- Flows.waitPolicy(quill, servedAt("recovery-short"))
            bare  <- Flows.waitPolicy(quill, servedAt("recovery-bare"))
            none  <- Flows.waitPolicy(quill, CurrentLocation.empty)
          yield assertTrue(
            short.maximum == java.time.Duration.ofSeconds(60),
            short.recurrentFactor == 0.5,
            short.ceiling == WaitPolicy.Default.ceiling,
            short.identifiedFactor == WaitPolicy.Default.identifiedFactor,
            bare == WaitPolicy.Default,
            none == WaitPolicy.Default
          )
        ,
        test("a report that is not ours waits the ceiling"):
          for
            backend <- testBackend
            console <- createSession(backend)
            fromIt  <- postRecovery(
              backend,
              console,
              clientReport(file = "<anonymous>", line = 1, column = 1))
            elsewhere <- createSession(backend)
            outside   <- postRecovery(backend, elsewhere, clientReport(line = 3))
          yield assertTrue(
            offerOf(fromIt.body).exists(_.waitSeconds == 1800),
            offerOf(outside.body).exists(_.waitSeconds == 1800))
        ,
        test("an error another device reported, in its own engine's words, is recurrent"):
          for
            backend <- testBackend
            chrome  <- createSessionFrom(backend, "click-chrome", "AA:00:00:00:00:01")
            first   <- postRecovery(backend, chrome, clientReport())
            safari  <- createSessionFrom(backend, "click-safari", "AA:00:00:00:00:02")
            second  <- postRecovery(
              backend,
              safari,
              clientReport(
                file = "https://cdn.test/plaza-norte/main.js",
                message = "undefined is not an object (evaluating 'a.x')"))
            otherName <- postRecovery(backend, safari, clientReport(name = "RangeError"))
          yield assertTrue(
            offerOf(first.body).exists(o => o.waitSeconds == 300 && !o.recurrent),
            offerOf(second.body).exists(o => o.waitSeconds == 30 && o.recurrent),
            offerOf(otherName.body).exists(o => o.waitSeconds == 300 && !o.recurrent)
          )
        ,
        test("the waiting page is told when another device reports the same error"):
          ZIO.scoped:
            for
              backend <- testBackend
              first   <- createSessionFrom(backend, "click-first", "AA:00:00:00:00:01")
              offer   <- postRecovery(backend, first, clientReport())
              errorId = offerOf(offer.body).get.errorId
              events <- recoveryEvents(first, errorId)
              before <- events.take
              second <- createSessionFrom(backend, "click-second", "AA:00:00:00:00:02")
              _      <- postRecovery(backend, second, clientReport())
              // What the relay delivers once rqlite commits that report.
              _ <- ZIO.serviceWithZIO[RecoveryFeed](_.publish(RecoveryFeed.Signal.Recorded("any")))
              after    <- events.take
              errors   <- TestFixtures.getErrors
              tooEarly <- postRedeem(backend, first, errorId)
              _        <- TestClock.adjust(31.seconds)
              onTime   <- postRedeem(backend, first, errorId)
            yield assertTrue(
              before._1 == "offer",
              offerOf(before._2).exists(o => o.waitSeconds == 300 && !o.recurrent),
              // A tenth of the wait it was offered, counted from when it happened.
              after._1 == "offer",
              offerOf(after._2).exists(o => o.waitSeconds == 30 && o.recurrent),
              errors.exists(e => e.id == errorId && e.verdict.contains("recurrent")),
              errorOf(tooEarly.body).exists(_.isInstanceOf[ApiError.RecoveryNotYet]),
              decode[StatusResponse](onTime.body).toOption.exists(_.phase == Phase.Recovered)
            )
        ,
        test("a gap in the feed has the page checked again"):
          ZIO.scoped:
            for
              backend <- testBackend
              first   <- createSessionFrom(backend, "click-first", "AA:00:00:00:00:01")
              offer   <- postRecovery(backend, first, clientReport())
              errorId = offerOf(offer.body).get.errorId
              events <- recoveryEvents(first, errorId)
              _      <- events.take
              second <- createSessionFrom(backend, "click-second", "AA:00:00:00:00:02")
              _      <- postRecovery(backend, second, clientReport())
              _      <- ZIO.serviceWithZIO[RecoveryFeed](_.publish(RecoveryFeed.Signal.Gap))
              after  <- events.take
            yield assertTrue(offerOf(after._2).exists(_.recurrent))
        ,
        test("a stream for an error that is not the session's says so, and ends"):
          ZIO.scoped:
            for
              backend <- testBackend
              first   <- createSessionFrom(backend, "click-first", "AA:00:00:00:00:01")
              offer   <- postRecovery(backend, first, clientReport())
              errorId = offerOf(offer.body).get.errorId
              other  <- createSessionFrom(backend, "click-other", "AA:00:00:00:00:03")
              events <- recoveryEvents(other, errorId)
              failed <- events.take
            yield assertTrue(
              failed._1 == "failed",
              errorOf(failed._2).exists(_.isInstanceOf[ApiError.UnknownError]))
        ,
        test(
          "the feed reads an error recorded, by its fingerprint, out of what the relay hands on"):
          import whitelabel.captal.cdc.protocol.ChangeFeedFollower.Event
          val recorded =
            """{"index":7,"events":[{"op":"INSERT","table":"errors","after":{"id":"e-1","fingerprint":"f-1"}}]}"""
          val serverError =
            """{"index":8,"events":[{"op":"INSERT","table":"errors","after":{"id":"e-2","fingerprint":null}}]}"""
          val playback =
            """{"index":9,"events":[{"op":"INSERT","table":"video_playbacks","after":{"id":"p-1"}}]}"""
          val redeemed =
            """{"index":10,"events":[{"op":"UPDATE","table":"errors","after":{"id":"e-1","fingerprint":"f-1"}}]}"""
          assertTrue(
            RecoveryFeed.signalsOf(Event.Changed(7, recorded)) ==
              List(RecoveryFeed.Signal.Recorded("f-1")),
            RecoveryFeed.signalsOf(Event.Changed(8, serverError)) == Nil,
            RecoveryFeed.signalsOf(Event.Changed(9, playback)) == Nil,
            RecoveryFeed.signalsOf(Event.Changed(10, redeemed)) == Nil,
            RecoveryFeed.signalsOf(Event.Changed(11, "{")) == Nil,
            RecoveryFeed.signalsOf(Event.Gap) == List(RecoveryFeed.Signal.Gap)
          )
        ,
        test("the history counted is the user's on this device, by kind of effort"):
          for
            quill   <- ZIO.service[QuillSqlite]
            surveys <- TestFixtures.seedAllIdentificationSurveys
            ad      <- TestFixtures.seedAdvertiserWithVideoAndSurvey(questionCount = 2)
            someone <- TestFixtures.createUser("history@example.com")
            other   <- TestFixtures.createUser("other@example.com")
            here   = user.DeviceId.generate
            there  = user.DeviceId.generate
            answer =
              (q: question.Id, d: user.DeviceId) =>
                TestFixtures.createAnswer(someone.userId, user.SessionId.generate, q, "x", Some(d))
            _ <- answer(surveys.email.questionId, here)
            _ <- answer(surveys.profiling.questionId, here)
            _ <- ZIO.foreachDiscard(ad.questionIds)(answer(_, here))
            _ <- answer(surveys.location.questionId, there)
            _ <- TestFixtures.createVideoView(someone.userId, ad.videoId, here)
            _ <- TestFixtures.createVideoView(someone.userId, ad.videoId, here)
            _ <- TestFixtures.createVideoView(someone.userId, ad.videoId, there)
            repository = RecoveryRepositoryQuill(quill, BundleManifest.absent)
            onHere   <- repository.activity(someone.userId, here)
            onThere  <- repository.activity(someone.userId, there)
            borrowed <- repository.activity(other.userId, here)
          yield assertTrue(
            onHere ==
              Activity(
                videosWatched = 2,
                videoAnswers = 2,
                identificationAnswers = 1,
                emailAnswers = 1),
            onThere == Activity(videosWatched = 1, 0, identificationAnswers = 1, 0),
            // Typing someone's email on your own device borrows nothing of theirs.
            borrowed == Activity.Empty
          )
        ,
        test("ways out taken lately count against this device, and only within the window"):
          for
            quill <- ZIO.service[QuillSqlite]
            here  = user.DeviceId.generate
            there = user.DeviceId.generate
            day   = java.time.Duration.ofDays(1)
            _ <- TestFixtures.createClientError(
              here,
              "a",
              Now.minus(day.multipliedBy(10)),
              Some(Now.minus(day.multipliedBy(10))))
            _ <- TestFixtures.createClientError(
              here,
              "b",
              Now.minus(day.multipliedBy(40)),
              Some(Now.minus(day.multipliedBy(40))))
            _ <- TestFixtures.createClientError(here, "c", Now.minus(day))
            _ <- TestFixtures.createClientError(there, "d", Now.minus(day), Some(Now.minus(day)))
            recent <- RecoveryRepositoryQuill(quill, BundleManifest.absent).recoveriesSince(
              here,
              Now.minus(day.multipliedBy(30)))
          yield assertTrue(recent == 1)
        ,
        test("the same error counts from other devices within the window, never this one"):
          for
            quill <- ZIO.service[QuillSqlite]
            here  = user.DeviceId.generate
            there = user.DeviceId.generate
            again = user.DeviceId.generate
            day   = java.time.Duration.ofDays(1)
            _      <- TestFixtures.createClientError(here, "same", Now.minus(day))
            _      <- TestFixtures.createClientError(there, "same", Now.minus(day.multipliedBy(2)))
            _      <- TestFixtures.createClientError(there, "same", Now.minus(day.multipliedBy(3)))
            _      <- TestFixtures.createClientError(again, "same", Now.minus(day.multipliedBy(10)))
            _      <- TestFixtures.createClientError(again, "other", Now.minus(day))
            others <- RecoveryRepositoryQuill(quill, BundleManifest.absent).devicesReporting(
              ErrorFingerprint("same"),
              here,
              Now.minus(day.multipliedBy(7)))
          yield assertTrue(others == 1)
        ,
        test("another session cannot leave through this one's error"):
          for
            backend <- testBackend
            owner   <- createSession(backend)
            errorId <- TestFixtures.recordServerError(user.SessionId.unsafe(owner))
            other   <- createSession(backend)
            offer   <- postRecovery(backend, other, s"""{"serverErrorId":"$errorId"}""")
          yield assertTrue(errorOf(offer.body).contains(ApiError.UnknownError(errorId)))
        ,
        test("in the final phase there is no way out, only the code to show staff"):
          for
            backend   <- testBackend
            ready     <- createSession(backend)
            _         <- TestFixtures.setPhase(user.SessionId.unsafe(ready), Phase.Ready)
            atReady   <- postRecovery(backend, ready, clientReport())
            recovered <- createSession(backend)
            _         <- TestFixtures.setPhase(user.SessionId.unsafe(recovered), Phase.Recovered)
            again     <- postRecovery(backend, recovered, clientReport())
            errors    <- TestFixtures.getErrors
          yield assertTrue(
            List(atReady, again).forall(answer =>
              errorOf(answer.body) match
                case Some(ApiError.Unrecoverable(code)) =>
                  errors.exists(_.id == code)
                case _ =>
                  false))
      )

  private def servedAt(id: String): CurrentLocation = CurrentLocation(
    Some(id),
    Some(id),
    None,
    None)

  /** A bare location, its id and slug the same. */
  private def insertLocation(quill: QuillSqlite, id: String): ZIO[Any, Throwable, Unit] =
    import quill.*
    val now = Now.toString
    quill
      .run(
        query[LocationRow].insertValue(
          lift(LocationRow(id, id, id, isActive = 1, now, now, unifiApMac = None))))
      .unit
end RecoverySuite
