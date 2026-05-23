package whitelabel.captal.api.suites

import io.circe.parser.parse
import whitelabel.captal.api.TestFixtures
import whitelabel.captal.api.TestHelpers.*
import whitelabel.captal.core.application.Phase
import whitelabel.captal.core.user
import zio.Clock
import zio.test.*

object AuthorizedSuite:
  val suite: Spec[TestEnv, Throwable] =
    zio
      .test
      .suite("Authorized phase + access expiration")(
        test("Status returns Authorized + accessExpiresAt when session is authorized"):
          for
            backend      <- testBackend
            cookie       <- createSession(backend)
            sessionId    = user.SessionId.unsafe(cookie)
            now          <- Clock.instant
            futureExpiry = now.plusSeconds(3600)
            _            <- TestFixtures.setSessionAuthorized(sessionId, futureExpiry)
            statusResp   <- getStatus(backend, Some(cookie))
          yield
            val body            = parse(statusResp.body).toOption.get
            val phase           = body.hcursor.downField("phase").as[String].toOption.getOrElse("")
            val accessExpiresAt = body
              .hcursor
              .downField("accessExpiresAt")
              .focus
              .getOrElse(io.circe.Json.Null)
            assertTrue(
              statusResp.code.isSuccess,
              phase == "authorized",
              !accessExpiresAt.isNull
            )
        ,
        test("Status resets session to Welcome when accessExpiresAt is in the past"):
          for
            backend    <- testBackend
            cookie     <- createSession(backend)
            sessionId  = user.SessionId.unsafe(cookie)
            now        <- Clock.instant
            pastExpiry = now.minusSeconds(60)
            _          <- TestFixtures.setSessionAuthorized(sessionId, pastExpiry)
            statusResp <- getStatus(backend, Some(cookie))
            dbState    <- TestFixtures.queryDbState
          yield
            val body            = parse(statusResp.body).toOption.get
            val phase           = body.hcursor.downField("phase").as[String].toOption.getOrElse("")
            val accessExpiresAt = body
              .hcursor
              .downField("accessExpiresAt")
              .focus
              .getOrElse(io.circe.Json.Null)
            val sessionAfter = dbState.sessions.find(_.id == sessionId)
            assertTrue(
              statusResp.code.isSuccess,
              phase == "welcome",
              accessExpiresAt.isNull,
              sessionAfter.exists(_.phase == Phase.Welcome),
              sessionAfter.exists(_.accessExpiresAt.isEmpty),
              sessionAfter.exists(_.userId.isEmpty)
            )
        ,
        test("Finish handler skips UniFi authorization when no config (session stays in Ready)"):
          for
            _       <- TestFixtures.seedEmailSurvey
            fixture <- TestFixtures.seedAdvertiserWithVideoAndSurvey(questionCount = 1)
            backend <- testBackend
            cookie  <- createSession(backend)
            sessionId = user.SessionId.unsafe(cookie)
            _ <- getNextSurvey(backend, cookie)
            _ <- postEmailAnswer(backend, cookie, "finish-skip@example.com")
            _ <- getNextVideo(backend, cookie)
            _ <- postMarkVideoWatched(backend, cookie, 15, completed = true)
            _ <- getNextAdvertiserSurvey(backend, cookie)
            _ <- postAdvertiserAnswer(backend, cookie, fixture.optionIds.head.head.asString)
            _ <- postFinish(backend, cookie)
            dbState <- TestFixtures.queryDbState
          yield
            // No UnifiAccess in test layer (CurrentLocation.empty) — handler logs and skips.
            // Session must remain in Ready, accessExpiresAt unchanged.
            val sessionAfter = dbState.sessions.find(_.id == sessionId)
            assertTrue(
              sessionAfter.exists(_.phase == Phase.Ready),
              sessionAfter.exists(_.accessExpiresAt.isEmpty)
            )
      )
end AuthorizedSuite
