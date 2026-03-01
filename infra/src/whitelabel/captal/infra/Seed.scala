package whitelabel.captal.infra

import java.util.UUID

import io.getquill.*
import io.getquill.jdbczio.Quill
import whitelabel.captal.core.survey
import whitelabel.captal.infra.schema.QuillSqlite
import whitelabel.captal.infra.schema.core.given
import whitelabel.captal.infra.schema.given
import zio.*

/** Development-only seeder for populating the database with initial data. Run with: ./mill
  * infra.seed
  */
object Seed extends ZIOAppDefault:

  private val translations: List[(String, String, String, String)] = List(
    // Spanish translations
    ("welcome.title", "es", "frontend", "Bienvenido a Captal"),
    ("welcome.subtitle", "es", "frontend", "Gana dinero respondiendo encuestas y viendo anuncios"),
    ("welcome.steps.step1", "es", "frontend", "Responde encuestas cortas"),
    ("welcome.steps.step2", "es", "frontend", "Mira anuncios de marcas"),
    ("welcome.steps.step3", "es", "frontend", "Acumula puntos y canjea premios"),
    ("welcome.button.start", "es", "frontend", "Comenzar"),
    ("welcome.button.connecting", "es", "frontend", "Conectando..."),
    ("welcome.selectLanguage", "es", "frontend", "Selecciona tu idioma"),
    ("loading.message", "es", "frontend", "Cargando..."),
    ("error.title", "es", "frontend", "Ha ocurrido un error"),
    ("error.retry", "es", "frontend", "Reintentar"),
    ("error.generic", "es", "frontend", "Algo salió mal. Por favor, intenta de nuevo."),
    ("question.submit", "es", "frontend", "Enviar"),
    ("question.next", "es", "frontend", "Siguiente"),
    ("question.required", "es", "frontend", "Este campo es requerido"),
    ("question.invalidEmail", "es", "frontend", "Ingresa un correo válido"),
    ("question.invalidUrl", "es", "frontend", "Ingresa una URL válida"),
    ("question.invalidPattern", "es", "frontend", "El formato no es válido"),
    ("question.minLength", "es", "frontend", "Mínimo {min} caracteres"),
    ("question.maxLength", "es", "frontend", "Máximo {max} caracteres"),
    ("question.minSelections", "es", "frontend", "Selecciona al menos {min} opciones"),
    ("question.maxSelections", "es", "frontend", "Selecciona máximo {max} opciones"),
    ("question.invalidOption", "es", "frontend", "Opción no válida"),
    (
      "question.ratingOutOfRange",
      "es",
      "frontend",
      "La calificación debe estar entre {min} y {max}"),
    ("question.numericOutOfRange", "es", "frontend", "El valor debe estar entre {min} y {max}"),
    ("question.dateOutOfRange", "es", "frontend", "La fecha debe estar entre {min} y {max}"),
    ("question.invalidAnswer", "es", "frontend", "Respuesta no válida"),
    // English translations
    ("welcome.title", "en", "frontend", "Welcome to Captal"),
    ("welcome.subtitle", "en", "frontend", "Earn money by answering surveys and watching ads"),
    ("welcome.steps.step1", "en", "frontend", "Answer short surveys"),
    ("welcome.steps.step2", "en", "frontend", "Watch brand advertisements"),
    ("welcome.steps.step3", "en", "frontend", "Accumulate points and redeem rewards"),
    ("welcome.button.start", "en", "frontend", "Get Started"),
    ("welcome.button.connecting", "en", "frontend", "Connecting..."),
    ("welcome.selectLanguage", "en", "frontend", "Select your language"),
    ("loading.message", "en", "frontend", "Loading..."),
    ("error.title", "en", "frontend", "An error occurred"),
    ("error.retry", "en", "frontend", "Retry"),
    ("error.generic", "en", "frontend", "Something went wrong. Please try again."),
    ("question.submit", "en", "frontend", "Submit"),
    ("question.next", "en", "frontend", "Next"),
    ("question.required", "en", "frontend", "This field is required"),
    ("question.invalidEmail", "en", "frontend", "Please enter a valid email"),
    ("question.invalidUrl", "en", "frontend", "Please enter a valid URL"),
    ("question.invalidPattern", "en", "frontend", "The format is not valid"),
    ("question.minLength", "en", "frontend", "Minimum {min} characters"),
    ("question.maxLength", "en", "frontend", "Maximum {max} characters"),
    ("question.minSelections", "en", "frontend", "Select at least {min} options"),
    ("question.maxSelections", "en", "frontend", "Select at most {max} options"),
    ("question.invalidOption", "en", "frontend", "Invalid option"),
    ("question.ratingOutOfRange", "en", "frontend", "Rating must be between {min} and {max}"),
    ("question.numericOutOfRange", "en", "frontend", "Value must be between {min} and {max}"),
    ("question.dateOutOfRange", "en", "frontend", "Date must be between {min} and {max}"),
    ("question.invalidAnswer", "en", "frontend", "Invalid answer")
  )

  private def seedTranslations: ZIO[QuillSqlite, Throwable, Unit] = ZIO.serviceWithZIO[QuillSqlite]:
    quill =>
      import quill.{run as qrun, *}
      val now = java.time.Instant.now.toString
      val rows = translations.map: (entityId, locale, category, value) =>
        LocalizedTextRow(
          id = UUID.randomUUID.toString,
          entityId = entityId,
          locale = locale,
          value = value,
          category = category,
          createdAt = now,
          updatedAt = now)
      // Delete existing frontend translations and insert new ones
      qrun(query[LocalizedTextRow].filter(_.category == "frontend").delete) *>
        ZIO
          .foreach(rows): row =>
            qrun(query[LocalizedTextRow].insertValue(lift(row)))
          .unit

  private def seedEmailSurvey: ZIO[QuillSqlite, Throwable, Unit] = ZIO.serviceWithZIO[QuillSqlite]:
    quill =>
      import quill.{run as qrun, *}
      val now = java.time.Instant.now.toString
      val surveyId = survey.Id.generate
      val questionId = survey.question.Id.generate

      val surveyRow = SurveyRow(
        id = surveyId,
        category = "email",
        advertiserId = None,
        isActive = 1,
        createdAt = now)

      val questionRow = QuestionRow(
        id = questionId,
        surveyId = surveyId,
        questionType = "input",
        pointsAwarded = 10,
        displayOrder = 1,
        hierarchyLevel = None,
        isRequired = 1,
        createdAt = now)

      val emailRule = QuestionRuleRow(
        id = UUID.randomUUID.toString,
        questionId = questionId,
        ruleType = "text",
        ruleConfig = """{"type":"email"}""")

      val maxLengthRule = QuestionRuleRow(
        id = UUID.randomUUID.toString,
        questionId = questionId,
        ruleType = "text",
        ruleConfig = """{"type":"max_length","value":100}""")

      val questionTexts = List(
        LocalizedTextRow(
          id = UUID.randomUUID.toString,
          entityId = questionId.asString,
          locale = "es",
          value = "¿Cuál es tu correo electrónico?",
          category = "backend",
          createdAt = now,
          updatedAt = now
        ),
        LocalizedTextRow(
          id = UUID.randomUUID.toString,
          entityId = questionId.asString,
          locale = "en",
          value = "What is your email address?",
          category = "backend",
          createdAt = now,
          updatedAt = now
        ),
        // Placeholder texts
        LocalizedTextRow(
          id = UUID.randomUUID.toString,
          entityId = questionId.asString + "_placeholder",
          locale = "es",
          value = "correo@ejemplo.com",
          category = "backend",
          createdAt = now,
          updatedAt = now
        ),
        LocalizedTextRow(
          id = UUID.randomUUID.toString,
          entityId = questionId.asString + "_placeholder",
          locale = "en",
          value = "email@example.com",
          category = "backend",
          createdAt = now,
          updatedAt = now
        )
      )

      // Delete existing email surveys and insert new one
      qrun(query[SurveyRow].filter(_.category == "email").delete) *>
        qrun(query[SurveyRow].insertValue(lift(surveyRow))) *>
        qrun(query[QuestionRow].insertValue(lift(questionRow))) *>
        qrun(query[QuestionRuleRow].insertValue(lift(emailRule))) *>
        qrun(query[QuestionRuleRow].insertValue(lift(maxLengthRule))) *>
        ZIO.foreach(questionTexts)(row => qrun(query[LocalizedTextRow].insertValue(lift(row)))).unit

  private def seedProfilingSurvey
      : ZIO[QuillSqlite, Throwable, Unit] = ZIO.serviceWithZIO[QuillSqlite]: quill =>
    import quill.{run as qrun, *}
    val now = java.time.Instant.now.toString
    val surveyId = survey.Id.generate

    val surveyRow = SurveyRow(
      id = surveyId,
      category = "profiling",
      advertiserId = None,
      isActive = 1,
      createdAt = now)

    // Question 1: Name
    val nameQuestionId = survey.question.Id.generate
    val nameQuestion = QuestionRow(
      id = nameQuestionId,
      surveyId = surveyId,
      questionType = "input",
      pointsAwarded = 10,
      displayOrder = 1,
      hierarchyLevel = None,
      isRequired = 1,
      createdAt = now)
    val nameMaxLengthRule = QuestionRuleRow(
      id = UUID.randomUUID.toString,
      questionId = nameQuestionId,
      ruleType = "text",
      ruleConfig = """{"type":"max_length","value":100}""")
    val nameTexts = List(
      LocalizedTextRow(
        UUID.randomUUID.toString,
        nameQuestionId.asString,
        "es",
        "¿Cuál es tu nombre?",
        "backend",
        now,
        now),
      LocalizedTextRow(
        UUID.randomUUID.toString,
        nameQuestionId.asString,
        "en",
        "What is your name?",
        "backend",
        now,
        now),
      LocalizedTextRow(
        UUID.randomUUID.toString,
        nameQuestionId.asString + "_placeholder",
        "es",
        "Tu nombre completo",
        "backend",
        now,
        now),
      LocalizedTextRow(
        UUID.randomUUID.toString,
        nameQuestionId.asString + "_placeholder",
        "en",
        "Your full name",
        "backend",
        now,
        now)
    )

    // Question 2: Gender (radio)
    val genderQuestionId = survey.question.Id.generate
    val genderQuestion = QuestionRow(
      id = genderQuestionId,
      surveyId = surveyId,
      questionType = "radio",
      pointsAwarded = 10,
      displayOrder = 2,
      hierarchyLevel = None,
      isRequired = 1,
      createdAt = now)
    val genderMaleOptionId = survey.question.OptionId.generate
    val genderFemaleOptionId = survey.question.OptionId.generate
    val genderOtherOptionId = survey.question.OptionId.generate
    val genderOptions = List(
      QuestionOptionRow(genderMaleOptionId, genderQuestionId, 1, None),
      QuestionOptionRow(genderFemaleOptionId, genderQuestionId, 2, None),
      QuestionOptionRow(genderOtherOptionId, genderQuestionId, 3, None)
    )
    val genderTexts = List(
      LocalizedTextRow(
        UUID.randomUUID.toString,
        genderQuestionId.asString,
        "es",
        "¿Cuál es tu sexo?",
        "backend",
        now,
        now),
      LocalizedTextRow(
        UUID.randomUUID.toString,
        genderQuestionId.asString,
        "en",
        "What is your gender?",
        "backend",
        now,
        now),
      LocalizedTextRow(
        UUID.randomUUID.toString,
        genderMaleOptionId.asString,
        "es",
        "Masculino",
        "backend",
        now,
        now),
      LocalizedTextRow(
        UUID.randomUUID.toString,
        genderMaleOptionId.asString,
        "en",
        "Male",
        "backend",
        now,
        now),
      LocalizedTextRow(
        UUID.randomUUID.toString,
        genderFemaleOptionId.asString,
        "es",
        "Femenino",
        "backend",
        now,
        now),
      LocalizedTextRow(
        UUID.randomUUID.toString,
        genderFemaleOptionId.asString,
        "en",
        "Female",
        "backend",
        now,
        now),
      LocalizedTextRow(
        UUID.randomUUID.toString,
        genderOtherOptionId.asString,
        "es",
        "Otro",
        "backend",
        now,
        now),
      LocalizedTextRow(
        UUID.randomUUID.toString,
        genderOtherOptionId.asString,
        "en",
        "Other",
        "backend",
        now,
        now)
    )

    // Question 3: Date of Birth
    val dobQuestionId = survey.question.Id.generate
    val dobQuestion = QuestionRow(
      id = dobQuestionId,
      surveyId = surveyId,
      questionType = "date",
      pointsAwarded = 10,
      displayOrder = 3,
      hierarchyLevel = None,
      isRequired = 1,
      createdAt = now)
    val dobTexts = List(
      LocalizedTextRow(
        UUID.randomUUID.toString,
        dobQuestionId.asString,
        "es",
        "¿Cuál es tu fecha de nacimiento?",
        "backend",
        now,
        now),
      LocalizedTextRow(
        UUID.randomUUID.toString,
        dobQuestionId.asString,
        "en",
        "What is your date of birth?",
        "backend",
        now,
        now)
    )

    // Question 4: Phone number
    val phoneQuestionId = survey.question.Id.generate
    val phoneQuestion = QuestionRow(
      id = phoneQuestionId,
      surveyId = surveyId,
      questionType = "input",
      pointsAwarded = 10,
      displayOrder = 4,
      hierarchyLevel = None,
      isRequired = 1,
      createdAt = now)
    val phonePatternRule = QuestionRuleRow(
      id = UUID.randomUUID.toString,
      questionId = phoneQuestionId,
      ruleType = "text",
      ruleConfig = """{"type":"pattern","value":"^[0-9+\\-\\s]+$"}""")
    val phoneMaxLengthRule = QuestionRuleRow(
      id = UUID.randomUUID.toString,
      questionId = phoneQuestionId,
      ruleType = "text",
      ruleConfig = """{"type":"max_length","value":20}""")
    val phoneTexts = List(
      LocalizedTextRow(
        UUID.randomUUID.toString,
        phoneQuestionId.asString,
        "es",
        "¿Cuál es tu número de teléfono?",
        "backend",
        now,
        now),
      LocalizedTextRow(
        UUID.randomUUID.toString,
        phoneQuestionId.asString,
        "en",
        "What is your phone number?",
        "backend",
        now,
        now),
      LocalizedTextRow(
        UUID.randomUUID.toString,
        phoneQuestionId.asString + "_placeholder",
        "es",
        "+58 412 1234567",
        "backend",
        now,
        now),
      LocalizedTextRow(
        UUID.randomUUID.toString,
        phoneQuestionId.asString + "_placeholder",
        "en",
        "+1 555 1234567",
        "backend",
        now,
        now)
    )

    val allQuestions = List(nameQuestion, genderQuestion, dobQuestion, phoneQuestion)
    val allOptions = genderOptions
    val allRules = List(nameMaxLengthRule, phonePatternRule, phoneMaxLengthRule)
    val allTexts = nameTexts ++ genderTexts ++ dobTexts ++ phoneTexts

    qrun(query[SurveyRow].filter(_.category == "profiling").delete) *>
      qrun(query[SurveyRow].insertValue(lift(surveyRow))) *>
      ZIO.foreach(allQuestions)(q => qrun(query[QuestionRow].insertValue(lift(q)))) *>
      ZIO.foreach(allOptions)(o => qrun(query[QuestionOptionRow].insertValue(lift(o)))) *>
      ZIO.foreach(allRules)(r => qrun(query[QuestionRuleRow].insertValue(lift(r)))) *>
      ZIO.foreach(allTexts)(t => qrun(query[LocalizedTextRow].insertValue(lift(t)))).unit

  private def seedLocationSurvey
      : ZIO[QuillSqlite, Throwable, Unit] = ZIO.serviceWithZIO[QuillSqlite]: quill =>
    import quill.{run as qrun, *}
    val now = java.time.Instant.now.toString
    val surveyId = survey.Id.generate

    val surveyRow = SurveyRow(
      id = surveyId,
      category = "location",
      advertiserId = None,
      isActive = 1,
      createdAt = now)

    // Question 1: Estado (State)
    val stateQuestionId = survey.question.Id.generate
    val stateQuestion = QuestionRow(
      id = stateQuestionId,
      surveyId = surveyId,
      questionType = "select",
      pointsAwarded = 10,
      displayOrder = 1,
      hierarchyLevel = Some("state"),
      isRequired = 1,
      createdAt = now)

    // Venezuelan states as options
    val stateNames = List(
      "Amazonas",
      "Anzoátegui",
      "Apure",
      "Aragua",
      "Barinas",
      "Bolívar",
      "Carabobo",
      "Cojedes",
      "Delta Amacuro",
      "Distrito Capital",
      "Falcón",
      "Guárico",
      "Lara",
      "Mérida",
      "Miranda",
      "Monagas",
      "Nueva Esparta",
      "Portuguesa",
      "Sucre",
      "Táchira",
      "Trujillo",
      "Vargas",
      "Yaracuy",
      "Zulia"
    )

    val stateOptions = stateNames
      .zipWithIndex
      .map: (name, idx) =>
        val optionId = survey.question.OptionId.generate
        (QuestionOptionRow(optionId, stateQuestionId, idx + 1, None), optionId, name)

    val stateTexts = List(
      LocalizedTextRow(
        UUID.randomUUID.toString,
        stateQuestionId.asString,
        "es",
        "¿En qué estado vives?",
        "backend",
        now,
        now),
      LocalizedTextRow(
        UUID.randomUUID.toString,
        stateQuestionId.asString,
        "en",
        "What state do you live in?",
        "backend",
        now,
        now)
    )

    val stateOptionTexts = stateOptions.flatMap: (_, optionId, name) =>
      List(
        LocalizedTextRow(
          UUID.randomUUID.toString,
          optionId.asString,
          "es",
          name,
          "backend",
          now,
          now),
        LocalizedTextRow(
          UUID.randomUUID.toString,
          optionId.asString,
          "en",
          name,
          "backend",
          now,
          now)
      )

    val allTexts = stateTexts ++ stateOptionTexts

    qrun(query[SurveyRow].filter(_.category == "location").delete) *>
      qrun(query[SurveyRow].insertValue(lift(surveyRow))) *>
      qrun(query[QuestionRow].insertValue(lift(stateQuestion))) *>
      ZIO.foreach(stateOptions.map(_._1))(o =>
        qrun(query[QuestionOptionRow].insertValue(lift(o)))) *>
      ZIO.foreach(allTexts)(t => qrun(query[LocalizedTextRow].insertValue(lift(t)))).unit

  private val quillLayer = Quill.Sqlite.fromNamingStrategy(io.getquill.SnakeCase)
  private val dataSourceLayer = Quill.DataSource.fromPrefix("database")

  override val run: ZIO[Any, Throwable, Unit] =
    (
      for
        _ <- ZIO.logInfo("Seeding database...")
        _ <- seedTranslations
        _ <- ZIO.logInfo("Seeded translations")
        _ <- seedEmailSurvey
        _ <- ZIO.logInfo("Seeded email survey")
        _ <- seedProfilingSurvey
        _ <- ZIO.logInfo("Seeded profiling survey")
        _ <- seedLocationSurvey
        _ <- ZIO.logInfo("Seeded location survey")
        _ <- ZIO.logInfo("Seeding complete!")
      yield ()
    ).provide(dataSourceLayer >>> quillLayer)
end Seed
