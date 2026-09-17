package whitelabel.captal.mcp

import zio.test.*

/** The step list is the whole point of the readiness record, and it is pure, so it is tested
  * directly rather than through a database and an S3 bucket that would only be reporting what was
  * seeded. `Readiness.of` — the part that reads — is covered where LocalStack is available.
  */
object OnboardingSpec extends ZIOSpecDefault:

  private def readiness(
      locales: List[Readiness.LocaleGap] = List(Readiness.LocaleGap("es", 0)),
      hasStyles: Boolean = true,
      hasIcon: Boolean = true,
      activeVideos: Int = 1,
      pendingVideos: Int = 0,
      advertisers: Int = 1,
      unifi: Boolean = true) = Readiness.LocationReadiness(
    slug = "spec",
    name = "Spec",
    active = true,
    unifiConfigured = unifi,
    locales = locales,
    requiredKeys = 39,
    hasStyles = hasStyles,
    hasIcon = hasIcon,
    activeVideos = activeVideos,
    pendingVideos = pendingVideos,
    advertiserSurveys = activeVideos,
    advertisers = advertisers,
    nextSteps = Nil
  )

  def spec =
    suite("Onboarding")(
      test("a location with everything in place has nothing left"):
        assertTrue(Readiness.steps(readiness()) == Nil)
      ,
      test("no translations at all reads differently from an incomplete locale"):
        // The two are not the same failure: one renders raw key names to every guest, the other to
        // the guests of one language. An operator seeing "2 of 39 missing" knows to finish; one
        // seeing nothing at all needs to be told the portal is unusable.
        val none = Readiness.steps(readiness(locales = Nil))
        val partial = Readiness.steps(readiness(locales = List(Readiness.LocaleGap("en", 2))))
        assertTrue(
          none.exists(_.contains("No translations at all")),
          partial.exists(s => s.contains("'en'") && s.contains("2 of 39")),
          !partial.exists(_.contains("No translations at all"))
        )
      ,
      test("a video waiting for its file is named, not counted as missing"):
        // A created video with no object is invisible in the portal but is not a missing video: the
        // next step is the upload, not another create_advertiser_video.
        val waiting = Readiness.steps(readiness(activeVideos = 0, pendingVideos = 2))
        assertTrue(
          waiting.exists(s => s.contains("2 video(s) are waiting")),
          !waiting.exists(_.contains("No videos.")))
      ,
      test("advertisers come before videos, because a video needs one"):
        val steps = Readiness.steps(readiness(advertisers = 0, activeVideos = 0))
        val advertiser = steps.indexWhere(_.contains("create_advertiser"))
        val video = steps.indexWhere(_.contains("create_advertiser_video"))
        assertTrue(advertiser >= 0, video >= 0, advertiser < video)
      ,
      test("branding and UniFi are reported but never block"):
        // Neither stops a location from serving: the bundle has defaults, and UniFi is configured
        // outside this server. They are listed so the operator is not surprised, and last.
        val steps = Readiness.steps(readiness(hasStyles = false, hasIcon = false, unifi = false))
        assertTrue(
          steps.size == 3,
          steps.last.contains("UniFi"),
          steps.exists(_.contains("set_location_styles")),
          steps.exists(_.contains("set_location_icon")))
      ,
      test("the prompt says where you stopped and that stopping is fine"):
        // What distinguishes this from the skill it replaces: the skill describes the procedure,
        // this names the step you are on and tells the operator there is no half-written state to
        // be afraid of.
        val rendered = Prompts.render(
          readiness(locales = Nil, activeVideos = 0).pipe(r =>
            r.copy(nextSteps = Readiness.steps(r))))
        assertTrue(
          rendered.contains("Onboarding 'spec'"),
          rendered.contains("1. No translations at all"),
          rendered.contains("no commit step and no rollback"),
          // and it must not claim to deploy, because nothing here does
          rendered.contains("captal locations push")
        )
      ,
      test("a finished location renders as finished rather than as an empty list"):
        val rendered = Prompts.render(readiness())
        assertTrue(rendered.contains("Nothing here blocks a deploy."))
    )

  extension [A](self: A)
    private def pipe[B](f: A => B): B = f(self)
end OnboardingSpec
