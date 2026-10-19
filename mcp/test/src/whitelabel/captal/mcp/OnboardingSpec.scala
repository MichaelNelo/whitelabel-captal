package whitelabel.captal.mcp

import whitelabel.captal.mcp.resources.Readiness
import whitelabel.captal.mcp.tools.Prompts
import whitelabel.captal.platform.{LocationRow, UnifiAccess}
import zio.test.*

/** The step list of the readiness record, which is pure, so it is tested directly.
  *
  * `Readiness.of` — the part that reads — is **not** covered here: it needs both a database and an
  * S3 bucket, so the e2e that provisions a location end to end is where it belongs.
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
      // Defaults to the healthy case, so a test that wants a failure has to ask for it.
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
        val none = Readiness.steps(readiness(locales = Nil))
        val partial = Readiness.steps(readiness(locales = List(Readiness.LocaleGap("en", 2))))
        assertTrue(
          none.exists(_.contains("No translations at all")),
          partial.exists(s => s.contains("'en'") && s.contains("2 of 39")),
          !partial.exists(_.contains("No translations at all"))
        )
      ,
      test("a video waiting for its file is named, not counted as missing"):
        // A created video with no object needs an upload, not another create_advertiser_video.
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
        // Neither stops a location from serving, so both are listed last.
        val steps = Readiness.steps(readiness(hasStyles = false, hasIcon = false, unifi = false))
        assertTrue(
          steps.size == 3,
          steps.last.contains("UniFi"),
          steps.exists(_.contains("set_location_styles")),
          steps.exists(_.contains("set_location_icon")))
      ,
      test("the prompt says where you stopped and that stopping is fine"):
        val rendered = Prompts.render(
          readiness(locales = Nil, activeVideos = 0).pipe(r =>
            r.copy(nextSteps = Readiness.steps(r))))
        assertTrue(
          rendered.contains("Onboarding 'spec'"),
          rendered.contains("1. No translations at all"),
          rendered.contains("no commit step and no rollback"),
          // and it must not claim to deploy, but must name what does
          rendered.contains("Nothing here deploys"),
          rendered.contains("deploy_location"),
          // the CLI this used to name was deleted; a prompt is read by a model, which will try it
          !rendered.contains("captal locations")
        )
      ,
      test("a finished location renders as finished rather than as an empty list"):
        val rendered = Prompts.render(readiness())
        assertTrue(rendered.contains("Nothing here blocks a deploy."))
      ,
      // Without one, every visitor stops at the first screen.
      test("a missing identification survey is the first step, before anything else"):
        val steps = Readiness.steps(readiness(missingIdentificationSurveys = List("email")))
        assertTrue(
          steps.headOption.exists(_.contains("create_identification_survey")),
          steps.headOption.exists(_.contains("never identified")))
      ,
      test("it outranks a location with no translations either"):
        val steps = Readiness.steps(
          readiness(locales = Nil, missingIdentificationSurveys = List("email", "profiling")))
        assertTrue(
          steps.size >= 2,
          steps.head.contains("identification"),
          steps(1).contains("No translations at all"),
          // Both named in one step: the same tool creates them.
          steps.head.contains("email and profiling")
        )
      ,
      test("all three present leaves no step at all"):
        assertTrue(Readiness.steps(readiness()).forall(!_.contains("identification")))
      ,
      // The selection joins `advertisers` and filters on active, so a video whose advertiser was
      // retired is drawn by nothing while the counts still read as stocked.
      test("a video whose advertiser was retired is reported, not hidden behind the counts"):
        val steps = Readiness.steps(
          readiness(activeVideos = 3, adVideos = 3, advertiserSurveys = 3, strandedAdVideos = 2))
        assertTrue(
          steps.exists(s => s.contains("retired advertiser") && s.contains("2")),
          // Not the "no advertisers" step: there are active ones, just not this video's owner.
          steps.forall(!_.contains("No advertisers exist"))
        )
      ,
      // Counted against `adVideos` and not `activeVideos`: a promo plays with or without a survey.
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
      // `UnifiAccess.fromRow` needs host, siteId and apiToken together, and the step has to say so.
      test("the UniFi step names all three fields"):
        val steps = Readiness.steps(readiness(unifi = false))
        assertTrue(
          steps.exists(s =>
            s.contains("host") && s.contains("siteId") && s.contains("apiToken") &&
              s.contains("get_unifi_config")))
      ,
      // `fromRow` is what decides whether a guest is ever authorized: the handler skips on `None`
      // and leaves the session at Ready, with nothing said anywhere.
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
          // A host and nothing else.
          UnifiAccess.fromRow(row(token = None, site = None)).isEmpty,
          UnifiAccess.fromRow(row(site = None)).isEmpty,
          UnifiAccess.fromRow(row(token = None)).isEmpty,
          UnifiAccess.fromRow(row(host = None)).isEmpty
        )
    )

  extension [A](self: A)
    private def pipe[B](f: A => B): B = f(self)
end OnboardingSpec
