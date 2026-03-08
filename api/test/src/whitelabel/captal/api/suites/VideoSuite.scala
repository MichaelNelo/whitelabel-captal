package whitelabel.captal.api.suites

import whitelabel.captal.api.TestFixtures
import whitelabel.captal.api.TestHelpers.*
import whitelabel.captal.core.application.Phase
import zio.test.*

object VideoSuite:
  val suite: Spec[TestEnv, Throwable] =
    zio
      .test
      .suite("Video Endpoints")(
        test("GET /api/video/next returns video when in AdvertiserVideo phase"):
          for
            _            <- TestFixtures.seedEmailSurvey
            videoFixture <- TestFixtures.seedAdvertiserWithVideo()
            backend      <- testBackend
            cookie       <- createSession(backend)
            // Complete identification to reach AdvertiserVideo phase
            _         <- getNextSurvey(backend, cookie)
            _         <- postEmailAnswer(backend, cookie, "video-test@example.com")
            // Now in AdvertiserVideo phase, get next video
            videoResp <- getNextVideo(backend, cookie)
            parsed = parseNextVideo(videoResp.body)
          yield assertTrue(
            videoResp.code.isSuccess,
            parsed.isDefined,
            parsed.get.videoUrl == videoFixture.videoUrl,
            parsed.get.durationSeconds == videoFixture.durationSeconds
          )
        ,
        test("GET /api/video/next returns NextStep when no videos available"):
          for
            _       <- TestFixtures.seedEmailSurvey
            // No videos seeded
            backend <- testBackend
            cookie  <- createSession(backend)
            // Complete identification to reach AdvertiserVideo phase
            _         <- getNextSurvey(backend, cookie)
            _         <- postEmailAnswer(backend, cookie, "novideo@example.com")
            videoResp <- getNextVideo(backend, cookie)
            isStep = parseVideoStep(videoResp.body)
          yield assertTrue(
            videoResp.code.isSuccess,
            isStep
          )
        ,
        test("GET /api/video/next rejects non-AdvertiserVideo phase"):
          for
            _       <- TestFixtures.seedEmailSurvey
            backend <- testBackend
            cookie  <- createSession(backend)
            // Still in Welcome phase
            videoResp <- getNextVideo(backend, cookie)
          yield assertTrue(
            !videoResp.code.isSuccess || videoResp.body.contains("wrong_phase")
          )
        ,
        test("POST /api/video/watched marks video as watched and transitions to Ready"):
          for
            _            <- TestFixtures.seedEmailSurvey
            videoFixture <- TestFixtures.seedAdvertiserWithVideo()
            backend      <- testBackend
            cookie       <- createSession(backend)
            // Complete identification to reach AdvertiserVideo phase
            _         <- getNextSurvey(backend, cookie)
            _         <- postEmailAnswer(backend, cookie, "watched@example.com")
            // Get video (this assigns the video to session)
            _         <- getNextVideo(backend, cookie)
            // Mark video as watched
            watchResp <- postMarkVideoWatched(backend, cookie, 15, completed = true)
            parsed = parseVideoWatchedResponse(watchResp.body)
            // Check video views were created
            videoViews <- TestFixtures.getVideoViews
            dbState    <- TestFixtures.queryDbState
          yield assertTrue(
            watchResp.code.isSuccess,
            parsed.isDefined,
            parsed.get.nextPhase == "Ready",
            videoViews.nonEmpty,
            videoViews.exists(_.completed == 1),
            dbState.sessions.exists(_.phase == Phase.Ready)
          )
        ,
        test("POST /api/video/watched with partial viewing records duration"):
          for
            _            <- TestFixtures.seedEmailSurvey
            videoFixture <- TestFixtures.seedAdvertiserWithVideo(durationSeconds = 30)
            backend      <- testBackend
            cookie       <- createSession(backend)
            // Complete identification to reach AdvertiserVideo phase
            _ <- getNextSurvey(backend, cookie)
            _ <- postEmailAnswer(backend, cookie, "partial@example.com")
            // Get video
            _ <- getNextVideo(backend, cookie)
            // Mark as partially watched
            watchResp <- postMarkVideoWatched(backend, cookie, 10, completed = false)
            videoViews <- TestFixtures.getVideoViews
          yield assertTrue(
            watchResp.code.isSuccess,
            videoViews.nonEmpty,
            videoViews.exists(v => v.completed == 0 && v.durationWatchedSeconds == 10)
          )
        ,
        test("promo video is returned when no ad available"):
          for
            _         <- TestFixtures.seedEmailSurvey
            promo     <- TestFixtures.seedPromoVideo(videoUrl = "https://cdn.example.com/promo1.mp4")
            backend   <- testBackend
            cookie    <- createSession(backend)
            // Complete identification to reach AdvertiserVideo phase
            _         <- getNextSurvey(backend, cookie)
            _         <- postEmailAnswer(backend, cookie, "promo@example.com")
            videoResp <- getNextVideo(backend, cookie)
            parsed = parseNextVideo(videoResp.body)
          yield assertTrue(
            videoResp.code.isSuccess,
            parsed.isDefined,
            parsed.get.videoUrl == promo.videoUrl
          )
      )
end VideoSuite
