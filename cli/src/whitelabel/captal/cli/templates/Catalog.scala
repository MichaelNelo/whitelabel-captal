package whitelabel.captal.cli.templates

/** Template catalog — lists resource files for each init command. */
object Catalog:

  private val sharedFiles = List(
    "shared/surveys/email.yaml",
    "shared/surveys/profiling.yaml",
    "shared/surveys/location.yaml",
    "shared/captal.yaml",
    "shared/skills/add-survey.md",
    "shared/skills/add-advertiser.md",
    "shared/skills/deploy-shared.md"
  )

  private val locationFiles = List(
    "location/location.yaml",
    "location/i18n/es.yaml",
    "location/i18n/en.yaml",
    "location/assets/styles.css",
    "location/assets/brand-icon.svg",
    "location/skills/add-video.md",
    "location/skills/add-promo.md",
    "location/skills/edit-i18n.md",
    "location/skills/deploy-location.md"
  )

  /** Templates for `captal shared init` — strips the `shared/` prefix for output paths. */
  def sharedInitTemplates: List[Template] =
    Templates.loadAll(sharedFiles).map(t => t.copy(path = t.path.stripPrefix("shared/")))

  /** Templates for `captal init <slug>` — strips the `location/` prefix and replaces {{slug}}. */
  def initTemplates(slug: String): List[Template] =
    Templates
      .loadAll(locationFiles, Map("slug" -> slug))
      .map(t => t.copy(path = t.path.stripPrefix("location/")))

  /** Video template for `captal video` command. */
  def videoTemplate(videoName: String, advertiser: String, url: String): Template =
    Templates.loadAs(
      "video/video.yaml",
      s"videos/$videoName/video.yaml",
      Map("advertiser" -> advertiser, "url" -> url))

  /** Survey template for a video. */
  def videoSurveyTemplate(videoName: String, surveyName: String): Template =
    Templates.loadAs(
      "video/surveys/survey.yaml",
      s"videos/$videoName/surveys/$surveyName.yaml")

  /** Promo video template. */
  def promoTemplate(name: String, url: String): Template =
    Templates.loadAs(
      "video/promo.yaml",
      s"promo/$name.yaml",
      Map("url" -> url))
