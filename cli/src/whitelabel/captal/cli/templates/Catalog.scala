package whitelabel.captal.cli.templates

/** Template catalog — lists resource files for each command. */
object Catalog:

  // ─── shared templates (for captal init) ───────────────────────────────────

  private val sharedFiles = List(
    "shared/surveys/email.yaml",
    "shared/surveys/profiling.yaml",
    "shared/surveys/location.yaml",
    "shared/captal.yaml")

  /** Templates for `captal init` — shared resources, strips `shared/` prefix. */
  def sharedTemplates: List[Template] = Templates
    .loadAll(sharedFiles)
    .map(t => t.copy(path = t.path.stripPrefix("shared/")))

  // ─── skills templates (for captal init) ───────────────────────────────────

  private val skillsFiles = List(
    "skills/configure-aws/SKILL.md",
    "skills/yaml-reference/SKILL.md",
    "skills/add-survey/SKILL.md",
    "skills/add-advertiser/SKILL.md",
    "skills/deploy-shared/SKILL.md",
    "skills/add-video/SKILL.md",
    "skills/add-promo/SKILL.md",
    "skills/edit-i18n/SKILL.md",
    "skills/deploy-location/SKILL.md",
    "skills/add-location/SKILL.md",
    "skills/recover-data/SKILL.md",
    "skills/troubleshoot-deployment/SKILL.md"
  )

  /** Templates for `captal init` — AI skills, output to .agents/skills/. */
  def skillsTemplates: List[Template] = Templates.loadAll(skillsFiles)

  // ─── location templates (for captal locations add) ────────────────────────

  private val locationFiles = List(
    "location/location.yaml",
    "location/i18n/es.yaml",
    "location/i18n/en.yaml",
    "location/assets/styles.css",
    "location/assets/brand-icon.svg")

  /** Templates for `captal locations add <slug>` — strips `location/` prefix and replaces {{slug}}.
    */
  def locationTemplates(slug: String): List[Template] = Templates
    .loadAll(locationFiles, Map("slug" -> slug))
    .map(t => t.copy(path = t.path.stripPrefix("location/")))

  // ─── video templates ──────────────────────────────────────────────────────

  /** Video template for `captal video add` command. */
  def videoTemplate(videoName: String, advertiser: String, url: String): Template = Templates
    .loadAs(
      "video/video.yaml",
      s"videos/$videoName/video.yaml",
      Map("advertiser" -> advertiser, "url" -> url))

  /** Survey template for a video. */
  def videoSurveyTemplate(videoName: String, surveyName: String): Template = Templates.loadAs(
    "video/surveys/survey.yaml",
    s"videos/$videoName/surveys/$surveyName.yaml")

  /** Promo video template. */
  def promoTemplate(name: String, url: String): Template = Templates.loadAs(
    "video/promo.yaml",
    s"promo/$name.yaml",
    Map("url" -> url))
end Catalog
