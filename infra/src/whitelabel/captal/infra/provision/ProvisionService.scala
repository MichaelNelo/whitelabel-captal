package whitelabel.captal.infra.provision

import java.nio.file.{Files, Path, Paths}

import io.circe.yaml.parser as yamlParser
import whitelabel.captal.infra.schema.QuillSqlite
import zio.*

/** Provisioning service that syncs YAML configuration files to the database. Runs at server startup
  * as a ZLayer.
  */
object ProvisionService:

  // ─── Shared provisioning ────────────────────────────────────────────────────

  /** Provision shared resources (surveys + advertisers). Additive only — no deletes. */
  def runShared(quill: QuillSqlite, sharedDir: String): Task[Unit] =
    val baseDir = Paths.get(sharedDir)
    for
      _ <- ZIO.logInfo(s"Starting shared provisioning from $sharedDir")
      _ <- validateDirectory(baseDir)
      surveys = scanYamlFiles(baseDir.resolve("surveys"))
      _ <-
        ZIO.foreachDiscard(surveys) { (name, _) =>
          provisionSurvey(quill, baseDir.resolve(s"surveys/$name"), name.stripSuffix(".yaml"))
        }
      advertisers = scanYamlFiles(baseDir.resolve("advertisers"))
      _ <-
        ZIO.foreachDiscard(advertisers) { (name, _) =>
          provisionAdvertiser(
            quill,
            baseDir.resolve(s"advertisers/$name"),
            name.stripSuffix(".yaml"))
        }
      _ <- ZIO.logInfo("Shared provisioning complete")
    yield ()
  end runShared

  // ─── Location provisioning ──────────────────────────────────────────────────

  /** Provision location-specific resources (location, i18n, videos, promos). */
  def run(quill: QuillSqlite, provisionDir: String, locationSlug: String): Task[Unit] =
    for
      _ <- ZIO.logInfo(s"Starting provisioning from $provisionDir for location '$locationSlug'")
      baseDir = Paths.get(provisionDir)
      _ <- validateDirectory(baseDir)

      // 1. Read and provision location
      locationId = IdGenerator.locationId(locationSlug)
      _ <- provisionLocation(quill, baseDir, locationSlug, locationId)

      // 2. Load current manifest from DB (this location only)
      (dbManifest, localKeys) <- EntityWriter.loadManifest(quill)(locationId)

      // 3. Scan disk and compute hashes
      diskEntries <- scanDisk(baseDir, locationSlug)

      // 4. Compute plan (only local keys are eligible for deletion)
      plan    = ProvisionPlan.compute(diskEntries, dbManifest, localKeys)
      creates = plan.collect { case a: ProvisionPlan.Action.Create =>
        a
      }
      updates = plan.collect { case a: ProvisionPlan.Action.Update =>
        a
      }
      deletes = plan.collect { case a: ProvisionPlan.Action.Delete =>
        a
      }
      skips = plan.collect { case a: ProvisionPlan.Action.Skip =>
        a
      }
      _ <- ZIO.logInfo(
        s"Provision plan: ${creates.size} create, ${updates.size} update, ${deletes
            .size} delete, ${skips.size} skip")

      // 5. Execute creates and updates
      _ <-
        ZIO.foreachDiscard(creates ++ updates): action =>
          val (key, hash) =
            action match
              case ProvisionPlan.Action.Create(k, h) =>
                (k, h)
              case ProvisionPlan.Action.Update(k, h) =>
                (k, h)
              case _ =>
                throw new IllegalStateException("unreachable")
          provisionEntity(quill, baseDir, locationSlug, locationId, key) *>
            EntityWriter.upsertManifest(quill)(key, Some(locationId), hash)

      // 6. Execute deletes (soft-delete)
      _ <-
        ZIO.foreachDiscard(deletes): action =>
          softDeleteEntity(quill, action.entityKey, locationId) *>
            EntityWriter.deleteManifest(quill)(action.entityKey)

      _ <- ZIO.logInfo("Provisioning complete")
    yield ()

  // ─── Helpers ────────────────────────────────────────────────────────────────

  private def validateDirectory(dir: Path): Task[Unit] = ZIO.attempt:
    if !Files.exists(dir) then
      throw new RuntimeException(s"Provision directory does not exist: $dir")
    if !Files.isDirectory(dir) then
      throw new RuntimeException(s"Provision path is not a directory: $dir")

  private def provisionLocation(
      quill: QuillSqlite,
      baseDir: Path,
      slug: String,
      locationId: String): Task[Unit] =
    val locationFile = baseDir.resolve("location.yaml")
    for
      _ <-
        ZIO.when(!Files.exists(locationFile)):
          ZIO.fail(new RuntimeException(s"location.yaml not found in $baseDir"))
      content <- ZIO.attempt(Files.readString(locationFile))
      yaml    <- ZIO
        .fromEither(yamlParser.parse(content).flatMap(_.as[LocationYaml]))
        .mapError(e => new RuntimeException(s"Failed to parse location.yaml: $e"))
      _ <- EntityWriter.upsertLocation(quill)(locationId, slug, yaml.name, yaml.ap_mac)
      _ <- ZIO.logInfo(s"Provisioned location: $slug -> $locationId")
    yield ()

  /** Scan location-specific YAML files on disk and compute their content hashes. */
  private def scanDisk(baseDir: Path, locationSlug: String): Task[Map[String, String]] = ZIO
    .attempt:
      val entries = scala.collection.mutable.Map.empty[String, String]

      // i18n files
      scanYamlFiles(baseDir.resolve("i18n")).foreach: (name, content) =>
        val locale = name.stripSuffix(".yaml")
        entries += s"i18n:$locationSlug/$locale" -> ProvisionPlan.sha256(content)

      // video directories: videos/<videoSlug>/video.yaml + surveys/*.yaml
      val videosDir = baseDir.resolve("videos")
      if Files.exists(videosDir) then
        Files
          .list(videosDir)
          .filter(Files.isDirectory(_))
          .forEach: videoDir =>
            val videoSlug = videoDir.getFileName.toString
            val videoFile = videoDir.resolve("video.yaml")
            if Files.exists(videoFile) then
              entries += s"video:$locationSlug/$videoSlug" ->
                ProvisionPlan.sha256(Files.readAllBytes(videoFile))

              // surveys within this video
              scanYamlFiles(videoDir.resolve("surveys")).foreach: (name, content) =>
                val surveySlug = name.stripSuffix(".yaml")
                entries += s"video-survey:$locationSlug/$videoSlug/$surveySlug" ->
                  ProvisionPlan.sha256(content)

      // promo videos
      scanYamlFiles(baseDir.resolve("promo")).foreach: (name, content) =>
        val videoSlug = name.stripSuffix(".yaml")
        entries += s"promo:$locationSlug/$videoSlug" -> ProvisionPlan.sha256(content)

      entries.toMap

  private def scanYamlFiles(dir: Path): List[(String, Array[Byte])] =
    if !Files.exists(dir) then
      Nil
    else
      val result = scala.collection.mutable.ListBuffer.empty[(String, Array[Byte])]
      Files
        .list(dir)
        .filter(p => p.toString.endsWith(".yaml"))
        .forEach: path =>
          result += path.getFileName.toString -> Files.readAllBytes(path)
      result.toList

  /** Provision a single entity from its entity key. */
  private def provisionEntity(
      quill: QuillSqlite,
      baseDir: Path,
      locationSlug: String,
      locationId: String,
      entityKey: String): Task[Unit] =
    entityKey match
      case key if key.startsWith("i18n:") =>
        val parts = key.stripPrefix("i18n:").split("/")
        val locale = parts.last
        provisionI18n(quill, baseDir.resolve(s"i18n/$locale.yaml"), locale, locationId)

      case key if key.startsWith("video-survey:") =>
        // video-survey:{location}/{videoSlug}/{surveySlug}
        val parts = key.stripPrefix("video-survey:").split("/")
        if parts.length == 3 then
          val videoSlug = parts(1)
          val surveySlug = parts(2)
          provisionVideoSurvey(
            quill,
            baseDir.resolve(s"videos/$videoSlug/surveys/$surveySlug.yaml"),
            locationSlug,
            locationId,
            videoSlug,
            surveySlug)
        else
          ZIO.fail(new RuntimeException(s"Invalid video-survey entity key: $entityKey"))

      case key if key.startsWith("video:") =>
        // video:{location}/{videoSlug}
        val parts = key.stripPrefix("video:").split("/")
        if parts.length == 2 then
          val videoSlug = parts(1)
          provisionVideo(
            quill,
            baseDir.resolve(s"videos/$videoSlug/video.yaml"),
            locationSlug,
            locationId,
            videoSlug)
        else
          ZIO.fail(new RuntimeException(s"Invalid video entity key: $entityKey"))

      case key if key.startsWith("promo:") =>
        val parts = key.stripPrefix("promo:").split("/")
        if parts.length == 2 then
          val videoSlug = parts(1)
          provisionPromo(
            quill,
            baseDir.resolve(s"promo/$videoSlug.yaml"),
            locationSlug,
            locationId,
            videoSlug)
        else
          ZIO.fail(new RuntimeException(s"Invalid promo entity key: $entityKey"))

      case _ =>
        ZIO.logWarning(s"Unknown entity key: $entityKey")

  // ─── Entity provisioning ────────────────────────────────────────────────────

  private def provisionI18n(
      quill: QuillSqlite,
      path: Path,
      locale: String,
      locationId: String): Task[Unit] =
    for
      content      <- ZIO.attempt(Files.readString(path))
      translations <- ZIO
        .fromEither(yamlParser.parse(content).flatMap(_.as[I18nYaml]))
        .mapError(e => new RuntimeException(s"Failed to parse i18n $locale: $e"))
      _ <- EntityWriter.upsertI18n(quill)(locale, locationId, translations)
      _ <- ZIO.logInfo(s"Provisioned i18n: $locale (${translations.size} keys)")
    yield ()

  private def provisionSurvey(quill: QuillSqlite, path: Path, surveyName: String): Task[Unit] =
    for
      content <- ZIO.attempt(Files.readString(path))
      yaml    <- ZIO
        .fromEither(yamlParser.parse(content).flatMap(_.as[SurveyYaml]))
        .mapError(e => new RuntimeException(s"Failed to parse survey $surveyName: $e"))
      _ <- validateSurvey(surveyName, yaml)
      surveyId  = IdGenerator.surveyId(yaml.category)
      surveyKey = s"survey:${yaml.category}"
      _ <- EntityWriter.upsertSurvey(quill)(surveyId, yaml.category, None, None, None)
      _ <- provisionQuestions(quill, surveyKey, surveyId, yaml.questions)
      _ <- ZIO.logInfo(s"Provisioned survey: ${yaml.category} (${yaml.questions.size} questions)")
    yield ()

  private def provisionAdvertiser(
      quill: QuillSqlite,
      path: Path,
      advertiserSlug: String): Task[Unit] =
    for
      content <- ZIO.attempt(Files.readString(path))
      yaml    <- ZIO
        .fromEither(yamlParser.parse(content).flatMap(_.as[AdvertiserYaml]))
        .mapError(e => new RuntimeException(s"Failed to parse advertiser $advertiserSlug: $e"))
      advertiserId = IdGenerator.advertiserId(advertiserSlug)
      _ <- EntityWriter.upsertAdvertiser(quill)(advertiserId, yaml.name, yaml.priority)
      _ <- ZIO.logInfo(s"Provisioned advertiser: ${yaml.name} ($advertiserSlug)")
    yield ()

  private def provisionVideo(
      quill: QuillSqlite,
      path: Path,
      locationSlug: String,
      locationId: String,
      videoSlug: String): Task[Unit] =
    for
      content <- ZIO.attempt(Files.readString(path))
      yaml    <- ZIO
        .fromEither(yamlParser.parse(content).flatMap(_.as[VideoYaml]))
        .mapError(e => new RuntimeException(s"Failed to parse video $videoSlug: $e"))
      _ <-
        ZIO.when(yaml.url.isEmpty):
          ZIO.fail(new RuntimeException(s"Video URL cannot be empty: $videoSlug"))
      advertiserId = IdGenerator.advertiserId(yaml.advertiser)
      videoId      = IdGenerator.videoId(locationSlug, yaml.advertiser, videoSlug)
      _ <-
        EntityWriter.upsertVideo(quill)(
          videoId,
          Some(advertiserId),
          "publicidad",
          yaml.url,
          yaml.duration,
          yaml.minWatch,
          if yaml.showCountdown then
            1
          else
            0
          ,
          yaml.noRepeatSeconds,
          Some(locationId),
          yaml.priority
        )
      _ <- upsertLocalizedTexts(quill, videoId, yaml.title, "")
      _ <-
        yaml.description.fold(ZIO.unit)(desc => upsertLocalizedTexts(quill, videoId, desc, "_desc"))
      _ <- ZIO.logInfo(s"Provisioned video: $videoSlug (advertiser: ${yaml.advertiser})")
    yield ()

  private def provisionVideoSurvey(
      quill: QuillSqlite,
      path: Path,
      locationSlug: String,
      locationId: String,
      videoSlug: String,
      surveySlug: String): Task[Unit] =
    for
      content <- ZIO.attempt(Files.readString(path))
      // First read video.yaml to get the advertiser slug
      videoYamlPath = path.getParent.getParent.resolve("video.yaml")
      videoContent <- ZIO.attempt(Files.readString(videoYamlPath))
      videoYaml    <- ZIO
        .fromEither(yamlParser.parse(videoContent).flatMap(_.as[VideoYaml]))
        .mapError(e => new RuntimeException(s"Failed to parse video.yaml for $videoSlug: $e"))
      yaml <- ZIO
        .fromEither(yamlParser.parse(content).flatMap(_.as[VideoSurveyYaml]))
        .mapError(e => new RuntimeException(s"Failed to parse survey $videoSlug/$surveySlug: $e"))
      advertiserId = IdGenerator.advertiserId(videoYaml.advertiser)
      videoId      = IdGenerator.videoId(locationSlug, videoYaml.advertiser, videoSlug)
      surveyId     = IdGenerator.advertiserSurveyId(locationSlug, videoSlug, surveySlug)
      surveyKey    = s"video-survey:$locationSlug/$videoSlug/$surveySlug"
      _ <-
        EntityWriter.upsertSurvey(quill)(
          surveyId,
          "advertiser",
          Some(advertiserId),
          Some(videoId),
          Some(locationId),
          yaml.name)
      _ <- provisionQuestions(quill, surveyKey, surveyId, yaml.questions)
      _ <- ZIO.logInfo(s"Provisioned video survey: $videoSlug/$surveySlug")
    yield ()

  private def provisionPromo(
      quill: QuillSqlite,
      path: Path,
      locationSlug: String,
      locationId: String,
      videoSlug: String): Task[Unit] =
    for
      content <- ZIO.attempt(Files.readString(path))
      yaml    <- ZIO
        .fromEither(yamlParser.parse(content).flatMap(_.as[PromoVideoYaml]))
        .mapError(e => new RuntimeException(s"Failed to parse promo $videoSlug: $e"))
      _ <-
        ZIO.when(yaml.url.isEmpty):
          ZIO.fail(new RuntimeException(s"Promo video URL cannot be empty: $videoSlug"))
      videoId = IdGenerator.promoVideoId(locationSlug, videoSlug)
      _ <-
        EntityWriter.upsertVideo(quill)(
          videoId,
          None,
          "propaganda",
          yaml.url,
          yaml.duration,
          yaml.minWatch,
          if yaml.showCountdown then
            1
          else
            0
          ,
          None,
          Some(locationId),
          yaml.priority)
      _ <- upsertLocalizedTexts(quill, videoId, yaml.title, "")
      _ <-
        yaml.description.fold(ZIO.unit)(desc => upsertLocalizedTexts(quill, videoId, desc, "_desc"))
      _ <- ZIO.logInfo(s"Provisioned promo: $videoSlug")
    yield ()

  // ─── Questions ──────────────────────────────────────────────────────────────

  private def provisionQuestions(
      quill: QuillSqlite,
      surveyKey: String,
      surveyId: String,
      questions: List[QuestionYaml]): Task[Unit] =
    ZIO.foreachDiscard(questions.zipWithIndex): (q, idx) =>
      val questionId = IdGenerator.questionId(surveyKey, idx)
      for
        _ <- validateQuestion(surveyKey, idx, q)
        _ <-
          EntityWriter.upsertQuestion(quill)(
            questionId,
            surveyId,
            q.`type`,
            q.points,
            idx + 1,
            q.hierarchyLevel,
            if q.required then
              1
            else
              0)
        _ <- upsertLocalizedTexts(quill, questionId, q.text, "")
        _ <- q.description.fold(ZIO.unit)(d => upsertLocalizedTexts(quill, questionId, d, "_desc"))
        _ <-
          q.placeholder
            .fold(ZIO.unit)(p => upsertLocalizedTexts(quill, questionId, p, "_placeholder"))
        _ <-
          ZIO.foreachDiscard(q.options.getOrElse(Nil).zipWithIndex): (opt, oi) =>
            val optionId = IdGenerator.optionId(surveyKey, idx, oi)
            EntityWriter.upsertQuestionOption(quill)(optionId, questionId, oi + 1) *>
              upsertLocalizedTexts(quill, optionId, opt.text, "")
        _ <-
          ZIO.foreachDiscard(q.rules.getOrElse(Nil).zipWithIndex): (rule, ri) =>
            val ruleId = IdGenerator.ruleId(s"$surveyKey/$idx", ri)
            val ruleConfig =
              rule.value match
                case Some(v) =>
                  s"""{"type":"${rule.`type`}","value":${v.noSpaces}}"""
                case None =>
                  s"""{"type":"${rule.`type`}"}"""
            EntityWriter.upsertQuestionRule(quill)(ruleId, questionId, rule.`type`, ruleConfig)
      yield ()
      end for

  /** Upsert localized texts for multiple locales. */
  private def upsertLocalizedTexts(
      quill: QuillSqlite,
      entityId: String,
      texts: Map[String, String],
      suffix: String): Task[Unit] =
    val entityIdWithSuffix =
      if suffix.isEmpty then
        entityId
      else
        s"$entityId$suffix"
    ZIO.foreachDiscard(texts.toList): (locale, value) =>
      val id = IdGenerator.localizedTextId(entityId, locale, suffix)
      EntityWriter.upsertLocalizedText(quill)(id, entityIdWithSuffix, locale, value)

  /** Soft-delete an entity based on its key prefix. */
  private def softDeleteEntity(
      quill: QuillSqlite,
      entityKey: String,
      locationId: String): Task[Unit] =
    entityKey match
      // video:<locationSlug>/<videoSlug> — the advertiser slug isn't in the key, so we brute-force
      // by trying each active advertiser. `deactivateVideo` is a no-op UPDATE on a non-matching id.
      case key if key.startsWith("video:") =>
        val parts = key.stripPrefix("video:").split("/", 2)
        if parts.length == 2 then
          val (locationSlug, videoSlug) = (parts(0), parts(1))
          for
            advertisers <- EntityWriter.listActiveAdvertisers(quill)
            _ <- ZIO.foreachDiscard(advertisers): adv =>
              val candidate = IdGenerator.videoId(locationSlug, adv.id, videoSlug)
              EntityWriter.deactivateVideo(quill)(candidate)
          yield ()
        else
          ZIO.unit

      // promo:<locationSlug>/<videoSlug> — id is deterministic from those two parts.
      case key if key.startsWith("promo:") =>
        val parts = key.stripPrefix("promo:").split("/", 2)
        if parts.length == 2 then
          val id = IdGenerator.promoVideoId(parts(0), parts(1))
          EntityWriter.deactivateVideo(quill)(id)
        else
          ZIO.unit

      // video-survey:<locationSlug>/<videoSlug>/<surveySlug> — surveyId is uuid5 of those parts
      // (see provisionVideoSurvey which calls `advertiserSurveyId(locationSlug, videoSlug,
      // surveySlug)`). Deactivating the survey row stops the SPA from serving the question set;
      // existing answers stay in the `answers` table, linked to now-orphaned question_ids.
      case key if key.startsWith("video-survey:") =>
        val parts = key.stripPrefix("video-survey:").split("/", 3)
        if parts.length == 3 then
          val surveyId = IdGenerator.advertiserSurveyId(parts(0), parts(1), parts(2))
          EntityWriter.deactivateSurvey(quill)(surveyId)
        else
          ZIO.unit

      // i18n:<locationSlug>/<locale> — drop the localized_text rows for this location+locale.
      // The SPA falls back to `[<key>]` literals on missing translations.
      case key if key.startsWith("i18n:") =>
        val parts = key.stripPrefix("i18n:").split("/", 2)
        if parts.length == 2 then
          EntityWriter.deleteI18nForLocation(quill)(locationId, parts(1))
        else
          ZIO.unit

      case _ =>
        ZIO.logWarning(s"Don't know how to soft-delete: $entityKey")

  // ─── Validation ─────────────────────────────────────────────────────────────

  private val KebabCasePattern = "[a-z0-9-]+".r

  private def validateSurvey(name: String, yaml: SurveyYaml): Task[Unit] =
    ZIO
      .when(yaml.questions.isEmpty):
        ZIO.fail(new RuntimeException(s"Survey '$name' must have at least one question"))
      .unit

  private def validateQuestion(surveyKey: String, idx: Int, q: QuestionYaml): Task[Unit] =
    for
      _ <-
        ZIO.when(!q.text.contains("en")):
          ZIO.fail(
            new RuntimeException(s"Question $idx in '$surveyKey' must have at least an 'en' text"))
      _ <-
        ZIO.when(
          Set("radio", "checkbox", "dropdown").contains(q.`type`) && q.options.forall(_.size < 2)):
          ZIO.fail(
            new RuntimeException(
              s"Question $idx in '$surveyKey' (${q.`type`}) requires at least 2 options"))
    yield ()

  def validateFileNames(baseDir: Path): Task[Unit] = ZIO.attempt:
    def check(dir: Path): Unit =
      if Files.exists(dir) then
        Files
          .list(dir)
          .forEach: path =>
            val name = path.getFileName.toString.stripSuffix(".yaml")
            if !KebabCasePattern.matches(name) && Files.isRegularFile(path) then
              throw new RuntimeException(s"Invalid file name (must be kebab-case): $path")
            if Files.isDirectory(path) then
              if !KebabCasePattern.matches(path.getFileName.toString) then
                throw new RuntimeException(s"Invalid directory name (must be kebab-case): $path")
              check(path)
    check(baseDir)
end ProvisionService
