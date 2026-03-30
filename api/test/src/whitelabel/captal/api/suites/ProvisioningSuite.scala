package whitelabel.captal.api.suites

import java.nio.file.Paths

import io.getquill.*
import whitelabel.captal.api.TestFixtures
import whitelabel.captal.infra.*
import whitelabel.captal.infra.provision.{IdGenerator, ProvisionService}
import whitelabel.captal.infra.schema.QuillSqlite
import whitelabel.captal.infra.schema.core.given
import whitelabel.captal.infra.schema.given
import zio.ZIO
import zio.test.*

object ProvisioningSuite:
  private val locationSlug = "test-location"
  private val locationId = IdGenerator.locationId(locationSlug)

  private def resourceDir(scenario: String): String =
    val url = getClass.getResource(s"/provision/$scenario")
    if url == null then throw new RuntimeException(s"Test resource not found: /provision/$scenario")
    Paths.get(url.toURI).toString

  private def runProvision(scenario: String): ZIO[QuillSqlite, Throwable, Unit] =
    ZIO.serviceWithZIO[QuillSqlite]: quill =>
      ProvisionService.run(quill, resourceDir(scenario), locationSlug)

  val suite: Spec[QuillSqlite, Throwable] =
    zio
      .test
      .suite("Provisioning")(
        test("fresh provision creates all entities"):
          for
            _            <- TestFixtures.clearAllData
            _            <- runProvision("basic")
            locations    <- queryLocations
            advertisers  <- queryAdvertisers
            videos       <- queryVideos
            surveys      <- querySurveys
            questions    <- queryQuestions
            options      <- queryQuestionOptions
            i18nTexts    <- queryLocalizedTexts("frontend")
            backendTexts <- queryLocalizedTexts("backend")
            manifest     <- queryManifest
          yield
            val expectedAdvertiserId = IdGenerator.advertiserId("acme")
            val expectedVideoId =
              IdGenerator.videoId(locationSlug, "acme", "intro")
            val expectedPromoId =
              IdGenerator.promoVideoId(locationSlug, "welcome")
            val expectedSurveyId = IdGenerator.surveyId("email")
            val expectedAdvSurveyId =
              IdGenerator.advertiserSurveyId(locationSlug, "acme", "intro")

            assertTrue(
              // Location
              locations.size == 1,
              locations.head.slug == locationSlug,
              locations.head.name == "Test Location",
              locations.head.id == locationId,
              // Advertiser
              advertisers.size == 1,
              advertisers.head.id == expectedAdvertiserId,
              advertisers.head.name == "Acme Corp",
              advertisers.head.priority == 10,
              // Videos: 1 ad + 1 promo
              videos.size == 2,
              videos.exists(v =>
                v.id.asString == expectedVideoId &&
                  v.videoUrl == "https://cdn.example.com/acme-intro.mp4" &&
                  v.durationSeconds == 15 &&
                  v.videoType == "publicidad" &&
                  v.locationId.contains(locationId)),
              videos.exists(v =>
                v.id.asString == expectedPromoId &&
                  v.videoUrl == "https://cdn.example.com/promo-welcome.mp4" &&
                  v.videoType == "propaganda"),
              // Surveys: 1 global email + 1 advertiser survey
              surveys.size == 2,
              surveys.exists(s =>
                s.id.asString == expectedSurveyId &&
                  s.category == "email" &&
                  s.advertiserId.isEmpty),
              surveys.exists(s =>
                s.id.asString == expectedAdvSurveyId &&
                  s.category == "advertiser" &&
                  s.advertiserId.contains(expectedAdvertiserId)),
              // Questions: 1 for email + 1 for advertiser survey
              questions.size == 2,
              // Options: 2 for the advertiser survey question
              options.size == 2,
              // i18n texts: 2 keys * 2 locales = 4
              i18nTexts.size == 4,
              i18nTexts.exists(t => t.locale == "es" && t.value == "Bienvenido"),
              i18nTexts.exists(t => t.locale == "en" && t.value == "Welcome"),
              // Backend texts exist (video titles, question texts, etc.)
              backendTexts.nonEmpty,
              // Manifest has entries
              manifest.nonEmpty,
              manifest.exists(_.entityKey == "i18n:es"),
              manifest.exists(_.entityKey == "i18n:en"),
              manifest.exists(_.entityKey == "survey:email"),
              manifest.exists(_.entityKey == "advertiser:acme"),
              manifest.exists(_.entityKey == s"video:$locationSlug/acme/intro"),
              manifest.exists(_.entityKey == s"promo:$locationSlug/welcome")
            )
        ,
        test("re-provision with no changes is idempotent"):
          for
            _         <- TestFixtures.clearAllData
            _         <- runProvision("basic")
            manifest1 <- queryManifest
            _         <- runProvision("basic")
            manifest2 <- queryManifest
            videos    <- queryVideos
          yield assertTrue(
            manifest1.size == manifest2.size,
            manifest1.map(_.contentHash).toSet == manifest2.map(_.contentHash).toSet,
            // Still same number of entities
            videos.size == 2
          )
        ,
        test("provision detects updates"):
          for
            _ <- TestFixtures.clearAllData
            _ <- runProvision("basic")
            // Capture state before update
            advertisersBefore <- queryAdvertisers
            videosBefore      <- queryVideos
            manifestBefore    <- queryManifest
            _ <- runProvision("updated")
            // Capture state after update
            advertisersAfter <- queryAdvertisers
            videosAfter      <- queryVideos
            i18nTexts        <- queryLocalizedTexts("frontend")
            manifestAfter    <- queryManifest
          yield
            val expectedVideoId =
              IdGenerator.videoId(locationSlug, "acme", "intro")

            assertTrue(
              // Advertiser priority updated
              advertisersBefore.head.priority == 10,
              advertisersAfter.head.priority == 20,
              // Video url and duration updated
              videosBefore.find(_.id.asString == expectedVideoId).get.videoUrl ==
                "https://cdn.example.com/acme-intro.mp4",
              videosAfter.find(_.id.asString == expectedVideoId).get.videoUrl ==
                "https://cdn.example.com/acme-intro-v2.mp4",
              videosAfter.find(_.id.asString == expectedVideoId).get.durationSeconds == 20,
              // i18n texts updated
              i18nTexts.exists(t =>
                t.locale == "es" && t.entityId == "i18n.welcome" &&
                  t.value == "Bienvenido a Captal"),
              i18nTexts.exists(t =>
                t.locale == "en" && t.entityId == "i18n.welcome" &&
                  t.value == "Welcome to Captal"),
              // Manifest hashes changed for updated entities
              manifestBefore
                .find(_.entityKey == "advertiser:acme")
                .get
                .contentHash !=
                manifestAfter.find(_.entityKey == "advertiser:acme").get.contentHash,
              manifestBefore
                .find(_.entityKey == s"video:$locationSlug/acme/intro")
                .get
                .contentHash !=
                manifestAfter
                  .find(_.entityKey == s"video:$locationSlug/acme/intro")
                  .get
                  .contentHash
            )
        ,
        test("provision soft-deletes removed entities"):
          for
            _ <- TestFixtures.clearAllData
            _ <- runProvision("basic")
            // Verify entities exist and are active
            videosBefore   <- queryVideos
            manifestBefore <- queryManifest
            _ <- runProvision("reduced")
            videosAfter   <- queryAllVideos
            advertisers   <- queryAdvertisers
            manifestAfter <- queryManifest
          yield
            val introVideoId =
              IdGenerator.videoId(locationSlug, "acme", "intro")
            val promoVideoId =
              IdGenerator.promoVideoId(locationSlug, "welcome")

            assertTrue(
              // Before: 2 active videos
              videosBefore.size == 2,
              // After: intro video soft-deleted (is_active = 0)
              videosAfter.exists(v =>
                v.id.asString == introVideoId && v.isActive == 0),
              // Promo video soft-deleted
              videosAfter.exists(v =>
                v.id.asString == promoVideoId && v.isActive == 0),
              // Advertiser still active (directory still exists)
              advertisers.size == 1,
              advertisers.head.isActive == 1,
              // Manifest entries removed for deleted entities
              manifestBefore.exists(
                _.entityKey == s"video:$locationSlug/acme/intro"),
              !manifestAfter.exists(
                _.entityKey == s"video:$locationSlug/acme/intro"),
              manifestBefore.exists(
                _.entityKey == s"promo:$locationSlug/welcome"),
              !manifestAfter.exists(
                _.entityKey == s"promo:$locationSlug/welcome")
            )
        ,
        test("IDs are deterministic"):
          for
            _           <- TestFixtures.clearAllData
            _           <- runProvision("basic")
            locations   <- queryLocations
            advertisers <- queryAdvertisers
            videos      <- queryVideos
            surveys     <- querySurveys
          yield assertTrue(
            locations.head.id == IdGenerator.locationId(locationSlug),
            advertisers.head.id == IdGenerator.advertiserId("acme"),
            videos.exists(_.id.asString ==
              IdGenerator.videoId(locationSlug, "acme", "intro")),
            videos.exists(_.id.asString ==
              IdGenerator.promoVideoId(locationSlug, "welcome")),
            surveys.exists(_.id.asString == IdGenerator.surveyId("email")),
            surveys.exists(_.id.asString ==
              IdGenerator.advertiserSurveyId(locationSlug, "acme", "intro"))
          )
      ) @@ TestAspect.sequential

  // ─────────────────────────────────────────────────────────────────────────────
  // Query helpers
  // ─────────────────────────────────────────────────────────────────────────────

  private def queryLocations: ZIO[QuillSqlite, Throwable, List[LocationRow]] =
    ZIO.serviceWithZIO[QuillSqlite]: quill =>
      import quill.*
      run(query[LocationRow])

  private def queryAdvertisers: ZIO[QuillSqlite, Throwable, List[AdvertiserRow]] =
    ZIO.serviceWithZIO[QuillSqlite]: quill =>
      import quill.*
      run(query[AdvertiserRow].filter(_.isActive == 1))

  private def queryVideos: ZIO[QuillSqlite, Throwable, List[AdvertiserVideoRow]] =
    ZIO.serviceWithZIO[QuillSqlite]: quill =>
      import quill.*
      run(query[AdvertiserVideoRow].filter(_.isActive == 1))

  private def queryAllVideos: ZIO[QuillSqlite, Throwable, List[AdvertiserVideoRow]] =
    ZIO.serviceWithZIO[QuillSqlite]: quill =>
      import quill.*
      run(query[AdvertiserVideoRow])

  private def querySurveys: ZIO[QuillSqlite, Throwable, List[SurveyRow]] =
    ZIO.serviceWithZIO[QuillSqlite]: quill =>
      import quill.*
      run(query[SurveyRow])

  private def queryQuestions: ZIO[QuillSqlite, Throwable, List[QuestionRow]] =
    ZIO.serviceWithZIO[QuillSqlite]: quill =>
      import quill.*
      run(query[QuestionRow])

  private def queryQuestionOptions: ZIO[QuillSqlite, Throwable, List[QuestionOptionRow]] =
    ZIO.serviceWithZIO[QuillSqlite]: quill =>
      import quill.*
      run(query[QuestionOptionRow])

  private def queryLocalizedTexts(
      category: String): ZIO[QuillSqlite, Throwable, List[LocalizedTextRow]] =
    ZIO.serviceWithZIO[QuillSqlite]: quill =>
      import quill.*
      run(query[LocalizedTextRow].filter(_.category == lift(category)))

  private def queryManifest: ZIO[QuillSqlite, Throwable, List[ProvisionManifestRow]] =
    ZIO.serviceWithZIO[QuillSqlite]: quill =>
      import quill.*
      run(query[ProvisionManifestRow])
end ProvisioningSuite
