package whitelabel.captal.api.suites

import whitelabel.captal.api.TestFixtures
import whitelabel.captal.api.TestHelpers.*
import whitelabel.captal.core.application.Phase
import whitelabel.captal.core.user
import zio.test.*

object VideoSuite:
  val suite: Spec[TestEnv, Throwable] =
    zio
      .test
      .suite("Video Endpoints")(
        test("GET /api/video/next returns video when in AdvertiserVideo phase"):
          for
            _            <- TestFixtures.seedEmailSurvey
            videoFixture <- TestFixtures.seedAdvertiserWithVideoAndSurvey()
            backend      <- testBackend
            cookie       <- createSession(backend)
            // Complete identification to reach AdvertiserVideo phase
            _ <- getNextSurvey(backend, cookie)
            _ <- postEmailAnswer(backend, cookie, "video-test@example.com")
            // Now in AdvertiserVideo phase, get next video
            videoResp <- getNextVideo(backend, cookie)
            parsed = parseNextVideo(videoResp.body)
          yield assertTrue(
            videoResp.code.isSuccess,
            parsed.isDefined,
            parsed.get.videoUrl == videoFixture.videoUrl,
            parsed.get.durationSeconds == videoFixture.durationSeconds)
        ,
        test("GET /api/video/next returns NextStep when no videos available"):
          for
            _ <- TestFixtures.seedEmailSurvey
            // No videos seeded
            backend <- testBackend
            cookie  <- createSession(backend)
            // Complete identification to reach AdvertiserVideo phase
            _         <- getNextSurvey(backend, cookie)
            _         <- postEmailAnswer(backend, cookie, "novideo@example.com")
            videoResp <- getNextVideo(backend, cookie)
            isStep = parseVideoStep(videoResp.body)
          yield assertTrue(videoResp.code.isSuccess, isStep)
        ,
        test("GET /api/video/next rejects non-AdvertiserVideo phase"):
          for
            _       <- TestFixtures.seedEmailSurvey
            backend <- testBackend
            cookie  <- createSession(backend)
            // Still in Welcome phase
            videoResp <- getNextVideo(backend, cookie)
          yield assertTrue(!videoResp.code.isSuccess || videoResp.body.contains("WrongPhase"))
        ,
        test(
          "POST /api/video/watched marks video as watched and transitions to AdvertiserVideoSurvey"):
          for
            _            <- TestFixtures.seedEmailSurvey
            videoFixture <- TestFixtures.seedAdvertiserWithVideoAndSurvey()
            backend      <- testBackend
            cookie       <- createSession(backend)
            // Complete identification to reach AdvertiserVideo phase
            _ <- getNextSurvey(backend, cookie)
            _ <- postEmailAnswer(backend, cookie, "watched@example.com")
            // Get video (this assigns the video to session)
            _ <- getNextVideo(backend, cookie)
            // Mark video as watched
            watchResp <- postMarkVideoWatched(backend, cookie, 15, completed = true)
            parsed = parseVideoWatchedResponse(watchResp.body)
            // Check video views were created
            videoViews <- TestFixtures.getVideoViews
            dbState    <- TestFixtures.queryDbState
          yield assertTrue(
            watchResp.code.isSuccess,
            parsed.isDefined,
            parsed.get.nextPhase == "advertiser_video_survey",
            videoViews.nonEmpty,
            videoViews.exists(_.completed == 1),
            dbState.sessions.exists(_.phase == Phase.AdvertiserVideoSurvey)
          )
        ,
        test("POST /api/video/watched with partial viewing records duration"):
          for
            _            <- TestFixtures.seedEmailSurvey
            videoFixture <- TestFixtures.seedAdvertiserWithVideoAndSurvey(durationSeconds = 30)
            backend      <- testBackend
            cookie       <- createSession(backend)
            // Complete identification to reach AdvertiserVideo phase
            _ <- getNextSurvey(backend, cookie)
            _ <- postEmailAnswer(backend, cookie, "partial@example.com")
            // Get video
            _ <- getNextVideo(backend, cookie)
            // Mark as partially watched
            watchResp  <- postMarkVideoWatched(backend, cookie, 10, completed = false)
            videoViews <- TestFixtures.getVideoViews
          yield assertTrue(
            watchResp.code.isSuccess,
            videoViews.nonEmpty,
            videoViews.exists(v => v.completed == 0 && v.durationWatchedSeconds == 10))
        ,
        test("with no advertiser video available, the visitor gets a promo instead of nothing"):
          for
            _       <- TestFixtures.seedEmailSurvey
            promo   <- TestFixtures.seedPromoVideo(videoTitle = "House promo")
            backend <- testBackend
            cookie  <- createSession(backend)
            _       <- getNextSurvey(backend, cookie)
            _       <- postEmailAnswer(backend, cookie, "promo-fallback@example.com")
            // No advertiser was seeded, so nextAdQueryAuthenticated has nothing to return.
            videoResp <- getNextVideo(backend, cookie)
            parsed = parseNextVideo(videoResp.body)
          yield assertTrue(
            videoResp.code.isSuccess,
            parsed.isDefined,
            // NextVideo carries no id; the url is what identifies which video was served.
            parsed.get.videoUrl == promo.videoUrl,
            parsed.get.title.contains("House promo")
          )
        ,
        test("promos take turns per user, across that user's sessions"):
          for
            _      <- TestFixtures.seedEmailSurvey
            first  <- TestFixtures.seedPromoVideo(videoUrl = "https://cdn.example.com/first.mp4")
            second <- TestFixtures.seedPromoVideo(
              videoUrl = "https://cdn.example.com/second.mp4",
              priority = 2)
            backend <- testBackend
            a1      <- watchPromo(backend, "rotation-a@example.com")
            a2      <- watchPromo(backend, "rotation-a@example.com")
            a3      <- watchPromo(backend, "rotation-a@example.com")
            b1      <- watchPromo(backend, "rotation-b@example.com")
          yield assertTrue(
            // Each visit is a new session: the rotation follows the user, not the session.
            a1.contains(first.videoUrl),
            a2.contains(second.videoUrl),
            a3.contains(first.videoUrl),
            // Another user starts from the first, whatever the first user has watched.
            b1.contains(first.videoUrl)
          )
        ,
        test("a session is served only its own location's promos"):
          for
            _ <- TestFixtures.seedEmailSurvey
            _ <- TestFixtures.seedLocation("promo-elsewhere")
            _ <- TestFixtures.seedLocation("promo-here")
            // Earlier in rotation order, but another location's.
            _ <- TestFixtures.seedPromoVideo(
              videoUrl = "https://cdn.example.com/elsewhere.mp4",
              locationId = Some("promo-elsewhere"))
            here <- TestFixtures.seedPromoVideo(
              videoUrl = "https://cdn.example.com/here.mp4",
              priority = 2,
              locationId = Some("promo-here"))
            backend <- testBackend
            served  <- watchPromo(backend, "located@example.com", Some("promo-here"))
          yield assertTrue(served.contains(here.videoUrl))
      )

  /** One visit by `email` in a new session, placed at `locationId` when given: identify, take the
    * video served, watch it. The url served, which is what identifies the video.
    */
  private def watchPromo(
      backend: sttp.client3.SttpBackend[zio.Task, Any],
      email: String,
      locationId: Option[String] = None) =
    for
      cookie <- createSession(backend)
      _      <-
        zio
          .ZIO
          .foreachDiscard(locationId)(TestFixtures.placeSessionAt(user.SessionId.unsafe(cookie), _))
      _         <- getNextSurvey(backend, cookie)
      _         <- postEmailAnswer(backend, cookie, email)
      videoResp <- getNextVideo(backend, cookie)
      _         <- postMarkVideoWatched(backend, cookie, 10, completed = true)
    yield parseNextVideo(videoResp.body).map(_.videoUrl)
end VideoSuite
