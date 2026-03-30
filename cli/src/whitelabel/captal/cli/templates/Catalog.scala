package whitelabel.captal.cli.templates

import TemplateDsl.*

/** All CLI templates as pure data. */
object Catalog:

  // ─── Helpers ────────────────────────────────────────────────────────────────

  private def videoFields(
      url: String,
      duration: Int,
      minWatch: Int,
      showCountdown: Boolean,
      priority: Int
  ): String =
    Seq(
      "url" := url,
      "duration" := duration,
      "minWatch" := minWatch,
      "showCountdown" := showCountdown,
      "priority" := priority,
      "title" := Map("es" -> "TODO", "en" -> "TODO")
    ).mkString("\n")

  private def questionWithOptions(
      tpe: String,
      text: Map[String, String],
      options: List[Map[String, String]],
      points: Int = 10,
      required: Boolean = true,
      extra: Seq[String] = Seq.empty
  ): String =
    val entries = Seq(
      "type" := tpe,
      "points" := points,
      "required" := required
    ) ++ extra ++ Seq(
      "text" := text,
      section("options", options.map(m => item("text" := m))*)
    )
    item(entries*)

  private def questionWithRules(
      tpe: String,
      text: Map[String, String],
      placeholder: Map[String, String],
      rules: String*
  ): String =
    item(
      "type" := tpe,
      "points" := 10,
      "required" := true,
      "text" := text,
      "placeholder" := placeholder,
      section("rules", rules*)
    )

  // ─── Templates ──────────────────────────────────────────────────────────────

  def location(slug: String): Template =
    Template("location.yaml", lines("name" := slug))

  object i18n:
    val es: Template = Template(
      "i18n/es.yaml",
      lines(
        "welcome_title" := "Bienvenido",
        "welcome_subtitle" := "Responde encuestas y gana recompensas",
        "btn_continue" := "Continuar",
        "btn_submit" := "Enviar",
        "btn_skip" := "Omitir"
      )
    )

    val en: Template = Template(
      "i18n/en.yaml",
      lines(
        "welcome_title" := "Welcome",
        "welcome_subtitle" := "Answer surveys and earn rewards",
        "btn_continue" := "Continue",
        "btn_submit" := "Submit",
        "btn_skip" := "Skip"
      )
    )

  object surveys:
    val email: Template = Template(
      "surveys/email.yaml",
      lines(
        "category" := "email",
        section(
          "questions",
          questionWithRules(
            "input",
            Map("es" -> "¿Cuál es tu correo electrónico?", "en" -> "What is your email address?"),
            Map("es" -> "correo@ejemplo.com", "en" -> "email@example.com"),
            item("type" := "email"),
            item("type" := "max_length", "value" := 100)
          )
        )
      )
    )

    val profiling: Template = Template(
      "surveys/profiling.yaml",
      lines(
        "category" := "profiling",
        section(
          "questions",
          questionWithOptions(
            "radio",
            Map("es" -> "¿Cuál es tu rango de edad?", "en" -> "What is your age range?"),
            List(
              Map("es" -> "18-25", "en" -> "18-25"),
              Map("es" -> "26-35", "en" -> "26-35"),
              Map("es" -> "36-50", "en" -> "36-50"),
              Map("es" -> "50+", "en" -> "50+")
            )
          )
        )
      )
    )

    val location: Template = Template(
      "surveys/location.yaml",
      lines(
        "category" := "location",
        section(
          "questions",
          questionWithOptions(
            "dropdown",
            Map("es" -> "¿En qué estado te encuentras?", "en" -> "What state are you in?"),
            List(
              Map("es" -> "Distrito Capital", "en" -> "Capital District"),
              Map("es" -> "Miranda", "en" -> "Miranda")
            ),
            extra = Seq("hierarchyLevel" := "state")
          )
        )
      )
    )

  object assets:
    val stylesCss: Template = Template(
      "assets/styles.css",
      """/* Custom branding styles for this location */
        |:root {
        |  --brand-primary: #2563eb;
        |  --brand-secondary: #1e40af;
        |}
        |""".stripMargin
    )

    val brandIconSvg: Template = Template(
      "assets/brand-icon.svg",
      """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor">
        |  <circle cx="12" cy="12" r="10" fill="#2563eb"/>
        |</svg>
        |""".stripMargin
    )

  def advertiser(slug: String): Template =
    Template("advertiser.yaml", lines("name" := slug, "priority" := 10))

  object videos:
    def ad(name: String, url: String): Template = Template(
      s"videos/$name.yaml",
      lines(
        videoFields(url, 15, 5, true, 10),
        commented("description" := Map("es" -> "TODO", "en" -> "TODO")),
        commented(
          section(
            "survey",
            section(
              "questions",
              item(
                "type" := "radio",
                "points" := 10,
                "required" := true,
                "text" := Map("es" -> "TODO", "en" -> "TODO"),
                section(
                  "options",
                  item("text" := Map("es" -> "TODO", "en" -> "TODO")),
                  item("text" := Map("es" -> "TODO", "en" -> "TODO"))
                )
              )
            )
          )
        )
      )
    )

    def promo(name: String, url: String): Template =
      Template(s"promo/$name.yaml", lines(videoFields(url, 10, 3, false, 1)))

  def initTemplates(slug: String): List[Template] = List(
    location(slug),
    i18n.es,
    i18n.en,
    surveys.email,
    surveys.profiling,
    surveys.location,
    assets.stylesCss,
    assets.brandIconSvg
  )
