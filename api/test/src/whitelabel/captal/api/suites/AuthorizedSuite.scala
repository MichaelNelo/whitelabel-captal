package whitelabel.captal.api.suites

import io.circe.parser.parse
import whitelabel.captal.api.TestHelpers.*
import whitelabel.captal.api.{TestFixtures, TestUnifiClient}
import whitelabel.captal.core.application.Phase
import whitelabel.captal.core.user
import whitelabel.captal.platform.session.JobRunner
import zio.test.*
import zio.{Clock, ZIO, durationInt}

object AuthorizedSuite:
  val suite: Spec[TestEnv, Throwable] =
    zio
      .test
      .suite("Authorized phase + access expiration")(
        test("Status returns Authorized + secondsRemaining when session is authorized"):
          for
            backend <- testBackend
            cookie  <- createSession(backend)
            sessionId = user.SessionId.unsafe(cookie)
            now <- Clock.instant
            futureExpiry = now.plusSeconds(3600)
            _          <- TestFixtures.setSessionAuthorized(sessionId, futureExpiry)
            statusResp <- getStatus(backend, Some(cookie))
          yield
            val body = parse(statusResp.body).toOption.get
            val phase = body.hcursor.downField("phase").as[String].toOption.getOrElse("")
            val secondsRemaining = body.hcursor.downField("secondsRemaining").as[Int].toOption
            assertTrue(
              statusResp.code.isSuccess,
              phase == "authorized",
              // The only expiry the response carries: the countdown is seeded from this so it
              // never touches the device clock — a captive-portal client has had no internet and
              // may not have synced NTP.
              secondsRemaining.exists(s => s > 0 && s <= 3600)
            )
        ,
        test("Status resets session to Welcome when the access expiry is in the past"):
          for
            backend <- testBackend
            cookie  <- createSession(backend)
            sessionId = user.SessionId.unsafe(cookie)
            now <- Clock.instant
            pastExpiry = now.minusSeconds(60)
            _          <- TestFixtures.setSessionAuthorized(sessionId, pastExpiry)
            statusResp <- getStatus(backend, Some(cookie))
            dbState    <- TestFixtures.queryDbState
          yield
            val body = parse(statusResp.body).toOption.get
            val phase = body.hcursor.downField("phase").as[String].toOption.getOrElse("")
            val secondsRemaining = body
              .hcursor
              .downField("secondsRemaining")
              .focus
              .getOrElse(io.circe.Json.Null)
            val sessionAfter = dbState.sessions.find(_.id == sessionId)
            assertTrue(
              statusResp.code.isSuccess,
              phase == "welcome",
              secondsRemaining.isNull,
              sessionAfter.exists(_.phase == Phase.Welcome),
              sessionAfter.exists(_.accessExpiresAt.isEmpty),
              sessionAfter.exists(_.userId.isEmpty)
            )
        ,
        test("Finish leaves session in Ready when UniFi authorization fails"):
          for
            _       <- TestFixtures.seedEmailSurvey
            fixture <- TestFixtures.seedAdvertiserWithVideoAndSurvey(questionCount = 1)
            backend <- testBackend
            cookie  <- createSession(backend)
            sessionId = user.SessionId.unsafe(cookie)
            _       <- getNextSurvey(backend, cookie)
            _       <- postEmailAnswer(backend, cookie, "finish-skip@example.com")
            _       <- getNextVideo(backend, cookie)
            _       <- postMarkVideoWatched(backend, cookie, 15, completed = true)
            _       <- getNextAdvertiserSurvey(backend, cookie)
            _       <- postAdvertiserAnswer(backend, cookie, fixture.optionIds.head.head.asString)
            _       <- postFinish(backend, cookie)
            dbState <- TestFixtures.queryDbState
          yield
            // TestUnifiClient starts inactive and fails every call, reproducing an unresponsive
            // UCG: the session must stay in Ready so /api/finish can retry.
            val sessionAfter = dbState.sessions.find(_.id == sessionId)
            assertTrue(
              sessionAfter.exists(_.phase == Phase.Ready),
              sessionAfter.exists(_.accessExpiresAt.isEmpty))
        ,
        test("Finish triggers UniFi authorization → /status returns Authorized + secondsRemaining"):
          for
            _       <- TestUnifiClient.enable
            _       <- TestFixtures.seedEmailSurvey
            fixture <- TestFixtures.seedAdvertiserWithVideoAndSurvey(questionCount = 1)
            backend <- testBackend
            cookie  <- createSession(backend)
            _       <- getNextSurvey(backend, cookie)
            _       <- postEmailAnswer(backend, cookie, "finish-auth@example.com")
            _       <- getNextVideo(backend, cookie)
            _       <- postMarkVideoWatched(backend, cookie, 15, completed = true)
            _       <- getNextAdvertiserSurvey(backend, cookie)
            _       <- postAdvertiserAnswer(backend, cookie, fixture.optionIds.head.head.asString)
            finishResp <- postFinish(backend, cookie)
            statusResp <- getStatus(backend, Some(cookie))
          yield
            val body = parse(statusResp.body).toOption.get
            val phase = body.hcursor.downField("phase").as[String].toOption.getOrElse("")
            val secondsRemaining = body.hcursor.downField("secondsRemaining").as[Int].toOption
            assertTrue(
              finishResp.code.isSuccess,
              statusResp.code.isSuccess,
              phase == "authorized",
              // Presence, not value: TestUnifiClient grants access until the epoch, so the server
              // floors this at zero.
              secondsRemaining.isDefined
            )
        ,
        test("Access expiry: scheduler revokes on UniFi, resets the session and logs the event"):
          ZIO.scoped:
            for
              _ <- TestUnifiClient.enable
              // The runner comes from the layer graph, as in `Main`. Started here and not in
              // `testBackend`: with the scheduler running in every case and access that expires
              // instantly, the tests asserting "session is authorized" would become a race.
              runner <- ZIO.service[JobRunner]
              _      <- runner.start

              // ── Full flow over HTTP until the session is authorized ──
              _       <- TestFixtures.seedEmailSurvey
              fixture <- TestFixtures.seedAdvertiserWithVideoAndSurvey(questionCount = 1)
              backend <- testBackend
              cookie  <- createSession(backend)
              sessionId = user.SessionId.unsafe(cookie)
              _ <- getNextSurvey(backend, cookie)
              _ <- postEmailAnswer(backend, cookie, "expiry-flow@example.com")
              _ <- getNextVideo(backend, cookie)
              _ <- postMarkVideoWatched(backend, cookie, 15, completed = true)
              _ <- getNextAdvertiserSurvey(backend, cookie)
              _ <- postAdvertiserAnswer(backend, cookie, fixture.optionIds.head.head.asString)
              _ <- postFinish(backend, cookie)

              authorized <- TestFixtures.queryDbState.map(_.sessions.find(_.id == sessionId))

              // The deadline is already due: the test config uses `defaultDurationMinutes = 0`,
              // so `expiresAt == occurredAt` and the scheduler fires right away. The worker runs
              // on its own fiber and talks to rqlite, so it has to be awaited.
              expired <- awaitWithLiveClock:
                TestFixtures
                  .queryDbState
                  .map(_.sessions.find(_.id == sessionId).exists(_.phase == Phase.Welcome))

              after <- TestFixtures.queryDbState.map(_.sessions.find(_.id == sessionId))
              calls <- TestUnifiClient.calls
              log   <- TestFixtures.queryEventLog
            yield assertTrue(
              // the flow left the session authorized and with an expiration
              authorized.exists(_.phase == Phase.Authorized),
              authorized.exists(_.accessExpiresAt.isDefined),
              // the scheduler expired it without the client asking for anything
              expired,
              after.exists(_.phase == Phase.Welcome),
              after.exists(_.accessExpiresAt.isEmpty),
              after.exists(_.userId.isEmpty),
              // it was revoked on the UCG, after having been authorized
              calls.exists(_.isInstanceOf[TestUnifiClient.Call.Authorize]),
              calls.exists(_.isInstanceOf[TestUnifiClient.Call.Revoke]),
              // and it was recorded for analytics
              log.exists(_.eventType == "user.access_expired")
            )
      )

  /** The scheduler's worker runs on its own fiber and talks to rqlite, so the effect is not
    * observable synchronously. Bounded poll against the real clock.
    */
  private def awaitWithLiveClock[R](check: ZIO[R, Throwable, Boolean]): ZIO[R, Throwable, Boolean] =
    (ZIO.sleep(50.millis) *> check)
      .repeatUntil(identity)
      .timeoutTo(false)(identity)(15.seconds)
      .withClock(Clock.ClockLive)
end AuthorizedSuite
