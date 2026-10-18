package whitelabel.captal.mcp

import whitelabel.captal.platform.{LocationRow, UnifiAccess}
import zio.test.*

/** The step list is the whole point of the readiness record, and it is pure, so it is tested
  * directly rather than through a database and an S3 bucket that would only be reporting what was
  * seeded.
  *
  * `Readiness.of` — the part that reads — is **not** covered. This file used to claim it was
  * "covered where LocalStack is available"; nothing covers it, and that gap hid the sharpest of the
  * three bugs fixed here: `unifiConfigured` was computed from `unifiHost` alone, so a location
  * missing its `siteId` or token reported itself ready and then authorized nobody. The step text is
  * testable here; the field that feeds it is not, because `of` needs both a database and an S3
  * bucket. The e2e that provisions a location end to end is where it belongs.
  *
  * What is pinned below instead is the semantics the fix depends on: that all three UniFi fields
  * are required together.
  */
object OnboardingSpec extends ZIOSpecDefault:

  private def readiness(
      locales: List[Readiness.LocaleGap] = List(Readiness.LocaleGap("es", 0)),
      hasStyles: Boolean = true,
      hasIcon: Boolean = true,
      activeVideos: Int = 1,
      pendingVideos: Int = 0,
      advertisers: Int = 1,
      missingIdentificationSurveys: List[String] = Nil,
      unifi: Boolean = true,
      // Defaults to "every video in rotation is an advertiser's, each with a live survey, each
      // owned by an active advertiser" — the healthy case, so a test that wants one of the three
      // new failures has to ask for it.
      adVideos: Int = 1,
      advertiserSurveys: Int = 1,
      strandedAdVideos: Int = 0) = Readiness.LocationReadiness(
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
    advertiserSurveys = advertiserSurveys,
    adVideos = adVideos,
    strandedAdVideos = strandedAdVideos,
    advertisers = advertisers,
    missingIdentificationSurveys = missingIdentificationSurveys,
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
      ,
      // Readiness counted translations, branding, videos and advertisers and never asked whether a
      // guest could be identified at all. A location could report nothing left to do while every
      // visitor stopped at the first screen — the one failure here that is not a matter of degree.
      test("a missing identification survey is the first step, before anything else"):
        val steps = Readiness.steps(readiness(missingIdentificationSurveys = List("email")))
        assertTrue(
          steps.headOption.exists(_.contains("create_identification_survey")),
          steps.headOption.exists(_.contains("never identified")))
      ,
      // Ahead of the translations step, which is the loudest of the others: raw key names are
      // visible but the portal still works, while this stops the flow outright.
      test("it outranks a location with no translations either"):
        val steps = Readiness.steps(
          readiness(locales = Nil, missingIdentificationSurveys = List("email", "profiling")))
        assertTrue(
          steps.size >= 2,
          steps.head.contains("identification"),
          steps(1).contains("No translations at all"),
          // Both named in one step: they are created by the same tool and fixed in one sitting.
          steps.head.contains("email and profiling")
        )
      ,
      test("all three present leaves no step at all"):
        assertTrue(Readiness.steps(readiness()).forall(!_.contains("identification")))
      ,
      // The selection joins `advertisers` and filters on active, so a video whose advertiser was
      // retired is drawn by nothing. The location reads as stocked: `activeVideos` counts it and
      // `advertisers` counts the *other* advertisers that are still active.
      test("a video whose advertiser was retired is reported, not hidden behind the counts"):
        val steps = Readiness.steps(
          readiness(activeVideos = 3, adVideos = 3, advertiserSurveys = 3, strandedAdVideos = 2))
        assertTrue(
          steps.exists(s => s.contains("retired advertiser") && s.contains("2")),
          // Not the "no advertisers" step: there are active ones, just not this video's owner.
          steps.forall(!_.contains("No advertisers exist"))
        )
      ,
      // Counted against `adVideos` and not `activeVideos`, which is the correction: a promo plays
      // with or without a survey, so counting it here invented a problem for every location that
      // has one.
      test("a promo without a survey is not a missing survey"):
        val onePromo = readiness(
          activeVideos = 2,
          adVideos = 1,
          advertiserSurveys = 1,
          strandedAdVideos = 0)
        val oneAdShort = readiness(
          activeVideos = 2,
          adVideos = 2,
          advertiserSurveys = 1,
          strandedAdVideos = 0)
        assertTrue(
          Readiness.steps(onePromo).forall(!_.contains("no survey")),
          Readiness.steps(oneAdShort).exists(_.contains("no survey")))
      ,
      // `UnifiAccess.fromRow` needs host, siteId and apiToken together, and the step has to say so
      // — "not configured" sent the operator looking at the host, which was usually the one set.
      test("the UniFi step names all three fields"):
        val steps = Readiness.steps(readiness(unifi = false))
        assertTrue(
          steps.exists(s =>
            s.contains("host") && s.contains("siteId") && s.contains("apiToken") &&
              s.contains("get_unifi_config")))
      ,
      // What `Readiness.of` now asks instead of looking at the host. Pinned on `fromRow` because
      // that is the function whose answer decides whether a guest is ever authorized — the handler
      // skips on `None` and leaves the session at Ready, with nothing said anywhere.
      test("UniFi needs all three fields, which is what readiness has to report on"):
        def row(
            host: Option[String] = Some("10.0.0.1"),
            token: Option[String] = Some("tok"),
            site: Option[String] = Some("site-uuid")) = LocationRow(
          id = "l-1",
          slug = "spec",
          name = "Spec",
          isActive = 1,
          createdAt = "",
          updatedAt = "",
          unifiApMac = None,
          unifiHost = host,
          unifiApiToken = token,
          unifiSiteId = site)
        assertTrue(
          UnifiAccess.fromRow(row()).isDefined,
          // The case that used to read as configured: a host and nothing else.
          UnifiAccess.fromRow(row(token = None, site = None)).isEmpty,
          UnifiAccess.fromRow(row(site = None)).isEmpty,
          UnifiAccess.fromRow(row(token = None)).isEmpty,
          UnifiAccess.fromRow(row(host = None)).isEmpty
        )
    )

  extension [A](self: A)
    private def pipe[B](f: A => B): B = f(self)
end OnboardingSpec
