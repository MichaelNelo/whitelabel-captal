package whitelabel.captal.core.application

import utest.*
import whitelabel.captal.core.application.queries.{
  LocaleGap,
  LocationReadinessQuery,
  ReadinessReport,
  ReadinessStep
}

object ReadinessTests extends TestSuite:
  // Defaults to the healthy case, so a test that wants a failure has to ask for it.
  private def report(
      locales: List[LocaleGap] = List(LocaleGap("es", 0)),
      hasStyles: Boolean = true,
      hasIcon: Boolean = true,
      activeVideos: Int = 1,
      pendingVideos: Int = 0,
      advertisers: Int = 1,
      missingIdentificationSurveys: List[String] = Nil,
      unifi: Boolean = true,
      unifiEnabled: Boolean = true,
      adVideos: Int = 1,
      advertiserSurveys: Int = 1,
      strandedAdVideos: Int = 0,
      termsVersion: Option[Int] = Some(1),
      localesWithoutTerms: List[String] = Nil) = ReadinessReport(
    slug = "spec",
    name = "Spec",
    active = true,
    unifiConfigured = unifi,
    unifiEnabled = unifiEnabled,
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
    termsVersion = termsVersion,
    localesWithoutTerms = localesWithoutTerms,
    nextSteps = Nil
  )

  private def steps(r: ReadinessReport) = LocationReadinessQuery.nextSteps(r)

  val tests = Tests:
    test("a location with everything in place has nothing left"):
      assert(steps(report()) == Nil)

    test("no translations at all is a step of its own, apart from an incomplete locale"):
      val partial = steps(report(locales = List(LocaleGap("en", 2), LocaleGap("es", 0))))
      assert(steps(report(locales = Nil)) == List(ReadinessStep.NoTranslations))
      assert(partial == List(ReadinessStep.LocaleIncomplete("en", 2, 39)))

    // A created video with no file needs an upload, not another video.
    test("a video waiting for its file is named, not counted as missing"):
      assert(
        steps(report(activeVideos = 0, pendingVideos = 2)) ==
          List(ReadinessStep.VideosAwaitingFile(2)))

    test("advertisers come before videos, because a video needs one"):
      assert(
        steps(report(advertisers = 0, activeVideos = 0)) ==
          List(ReadinessStep.NoAdvertisers, ReadinessStep.NoVideos))

    test("branding and the access point come last"):
      assert(
        steps(report(locales = Nil, hasStyles = false, hasIcon = false, unifi = false)) ==
          List(
            ReadinessStep.NoTranslations,
            ReadinessStep.NoStyles,
            ReadinessStep.NoIcon,
            ReadinessStep.NoAccessPoint))

    // Without one, every guest stops at the first screen.
    test("missing identification surveys come first, named in one step"):
      assert(
        steps(report(locales = Nil, missingIdentificationSurveys = List("email", "profiling"))) ==
          List(
            ReadinessStep.IdentificationSurveysMissing(List("email", "profiling")),
            ReadinessStep.NoTranslations))

    test("the terms come right after them, since a deploy is refused without them"):
      val missing = steps(
        report(locales = Nil, termsVersion = None, missingIdentificationSurveys = List("email")))
      assert(
        missing ==
          List(
            ReadinessStep.IdentificationSurveysMissing(List("email")),
            ReadinessStep.NoTerms,
            ReadinessStep.NoTranslations))
      assert(
        steps(report(localesWithoutTerms = List("en"))) ==
          List(ReadinessStep.TermsWithoutLocales(List("en"))))

    // The selection joins `advertisers` and filters on active, so a video whose advertiser was retired
    // is drawn by nothing while the counts still read as stocked.
    test("a video whose advertiser was retired is reported, not hidden behind the counts"):
      assert(
        steps(
          report(activeVideos = 3, adVideos = 3, advertiserSurveys = 3, strandedAdVideos = 2)) ==
          List(ReadinessStep.AdVideosStranded(2)))

    // Counted against `adVideos`, not `activeVideos`: a promo plays with or without a survey.
    test("a promo without a survey is not a missing survey"):
      assert(steps(report(activeVideos = 2, adVideos = 1, advertiserSurveys = 1)) == Nil)
      assert(
        steps(report(activeVideos = 2, adVideos = 2, advertiserSurveys = 1)) ==
          List(ReadinessStep.AdVideosWithoutSurvey(1)))

    // The switch decides whether a controller is asked, not whether a guest gets past the first page:
    // that takes an access point either way.
    test("a location with UniFi turned off is still asked for an access point"):
      assert(
        steps(report(unifi = false, unifiEnabled = false)) == List(ReadinessStep.NoAccessPoint))
end ReadinessTests
