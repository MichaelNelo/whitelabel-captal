package whitelabel.captal.client.views

import com.raquo.laminar.api.L.*
import whitelabel.captal.client.{ApiClient, AppState, Router, Runtime}
import whitelabel.captal.client.i18n.I18nClient
import whitelabel.captal.core.Op
import whitelabel.captal.core.application.{IdentificationSurveyType, Phase}
import whitelabel.captal.core.application.commands.NextIdentificationSurvey
import whitelabel.captal.core.survey.Error as SurveyError
import whitelabel.captal.core.survey.question.{AnswerValue, QuestionOption, QuestionType, TextRule, ops as questionOps}
import whitelabel.captal.endpoints.SurveyResponse
import zio.*

object IdentificationQuestionView:
  private val answerValue: Var[Option[AnswerValue]] = Var(None)
  private val textInput: Var[String] = Var("")
  private val isSubmitting: Var[Boolean] = Var(false)
  private val validationError: Var[Option[String]] = Var(None)
  private val serverError: Var[Option[String]] = Var(None)
  private val isTouched: Var[Boolean] = Var(false)

  def render: HtmlElement =
    Layout(
      isLoading = AppState.currentSurvey.map(_.isEmpty),
      content = div(
        cls := "question-view",
        child <-- AppState.currentSurvey.map:
          case None         => div()
          case Some(survey) => questionsContent(survey)
        ,
        onMountCallback { _ => checkPhaseAndLoad() }
      ),
      footer = div(
        child.maybe <-- serverError.signal.map(_.map(msg => div(cls := "server-error", msg))),
        child <-- AppState.currentSurvey.map:
          case None => div()
          case Some(survey) =>
            button(
              cls := "question-submit-button",
              disabled <-- isSubmitting.signal.combineWith(answerValue.signal, validationError.signal).map:
                case (submitting, answer, validErr) => submitting || answer.isEmpty || validErr.isDefined,
              child.text <-- isSubmitting.signal.combineWith(I18nClient.i18n).map:
                case (true, _)     => "..."
                case (false, i18n) => i18n.question.submit
              ,
              onClick --> { _ => submitAnswer(survey) }
            )
      )
    )

  private def questionsContent(survey: NextIdentificationSurvey): HtmlElement =
    div(
      cls := "question-container",
      div(
        cls := "questions-list",
        questionCard(survey)
      )
    )

  private def checkPhaseAndLoad(): Unit =
    Runtime.run:
      for
        statusResult <- ApiClient.getStatus()
        _ <- ZIO.succeed:
          statusResult match
            case Right(status) if status.phase == Phase.Welcome =>
              // Redirect: user should not be here
              Router.syncWithPhase(Phase.Welcome)
            case Right(status) =>
              // OK, load question if not already in state
              AppState.getCurrentSurvey match
                case None    => loadQuestion()
                case Some(_) => ()
            case Left(_) =>
              // Error: redirect to welcome
              Router.syncWithPhase(Phase.Welcome)
      yield ()

  private def loadQuestion(): Unit =
    Runtime.run:
      ApiClient.getNextSurvey().map:
        case Right(SurveyResponse.Survey(survey)) =>
          AppState.setCurrentSurvey(survey)
        case Right(SurveyResponse.Step(nextStep)) =>
          AppState.setPhase(nextStep.phase)
          Router.syncWithPhase(nextStep.phase)
        case Left(_) =>
          ()

  private def questionCard(survey: NextIdentificationSurvey): HtmlElement =
    val cardStateSignal = isTouched.signal
      .combineWith(validationError.signal, answerValue.signal)
      .map:
        case (false, _, _)                  => "pristine"
        case (true, Some(_), _)             => "error"
        case (true, None, Some(_))          => "valid"
        case (true, None, None)             => "pristine"

    div(
      cls := "question-card",
      cls <-- cardStateSignal.map:
        case "error" => "card-error"
        case "valid" => "card-valid"
        case _       => ""
      ,
      h2(cls := "question-title", survey.question.text.value),
      survey.question.description.map(desc => p(cls := "question-description", desc.value)),
      div(
        cls := "question-input-area",
        renderQuestionType(survey.question.questionType, survey)
      ),
      child.maybe <-- validationError.signal.map(_.map(msg => div(cls := "validation-error", msg)))
    )

  private def validateAnswer(survey: NextIdentificationSurvey): Unit =
    isTouched.set(true)
    val q = survey.question
    answerValue.now() match
      case None =>
        // Check if required
        if q.commonRules.contains(whitelabel.captal.core.survey.question.CommonRule.Required) then
          validationError.set(Some(I18nClient.current.question.required))
        else
          validationError.set(None)
      case Some(answer) =>
        val result = Op.run(questionOps.validate(q.id, q.commonRules, q.questionType, answer))
        result match
          case Right(_) =>
            validationError.set(None)
          case Left(errors) =>
            val firstError = errors.head
            validationError.set(Some(surveyErrorToMessage(firstError)))

  private def surveyErrorToMessage(error: SurveyError): String =
    val q = I18nClient.current.question
    error match
      case SurveyError.RequiredAnswerMissing(_)            => q.required
      case SurveyError.InvalidEmail(_)                     => q.invalidEmail
      case SurveyError.InvalidUrl(_)                       => q.invalidUrl
      case SurveyError.InvalidPattern(_, _)                => q.invalidPattern
      case SurveyError.TextTooShort(_, min, _)             => q.minLength.replace("{min}", min.toString)
      case SurveyError.TextTooLong(_, max, _)              => q.maxLength.replace("{max}", max.toString)
      case SurveyError.TooFewSelections(_, min, _)         => q.minSelections.replace("{min}", min.toString)
      case SurveyError.TooManySelections(_, max, _)        => q.maxSelections.replace("{max}", max.toString)
      case SurveyError.InvalidOptionSelected(_, _)         => q.invalidOption
      case SurveyError.InvalidOptionsSelected(_, _)        => q.invalidOption
      case SurveyError.RatingOutOfRange(_, min, max, _)    => q.ratingOutOfRange.replace("{min}", min.toString).replace("{max}", max.toString)
      case SurveyError.NumericOutOfRange(_, min, max, _)   => q.numericOutOfRange.replace("{min}", min.toString).replace("{max}", max.toString)
      case SurveyError.DateOutOfRange(_, min, max, _)      => q.dateOutOfRange.replace("{min}", min.toString).replace("{max}", max.toString)
      case SurveyError.IncompatibleAnswerType(_, _)        => q.invalidAnswer
      case _                                               => I18nClient.current.error.generic

  private def renderQuestionType(qt: QuestionType, survey: NextIdentificationSurvey): HtmlElement =
    qt match
      case QuestionType.Input(rules)             => renderInput(rules, survey)
      case QuestionType.Radio(options)           => renderRadio(options, survey)
      case QuestionType.Select(options)          => renderSelect(options, survey)
      case QuestionType.Checkbox(options, rules) => renderCheckbox(options, survey)
      case QuestionType.Rating(rules)            => renderRating(survey)
      case QuestionType.Numeric(rules)           => renderNumeric(survey)
      case QuestionType.Date(rules)              => renderDate(survey)

  private def renderInput(rules: List[TextRule], survey: NextIdentificationSurvey): HtmlElement =
    val isEmail = rules.exists:
      case TextRule.Email => true
      case _              => false

    input(
      cls := "text-input",
      typ := (if isEmail then "email" else "text"),
      placeholder := survey.question.placeholder.map(_.value).getOrElse(""),
      controlled(
        value <-- textInput.signal,
        onInput.mapToValue --> { v =>
          textInput.set(v)
          if v.nonEmpty then answerValue.set(Some(AnswerValue.Text(v)))
          else answerValue.set(None)
        }
      ),
      onBlur --> { _ => validateAnswer(survey) }
    )

  private def renderRadio(options: List[QuestionOption], survey: NextIdentificationSurvey): HtmlElement =
    div(
      cls := "radio-group",
      options.sortBy(_.displayOrder).map: opt =>
        label(
          cls := "radio-option",
          input(
            typ := "radio",
            nameAttr := "question-radio",
            value := opt.id.asString,
            onChange.mapToValue --> { _ =>
              answerValue.set(Some(AnswerValue.SingleChoice(opt.id)))
              validateAnswer(survey)
            }
          ),
          span(cls := "radio-label", opt.text.value)
        )
    )

  private def renderSelect(options: List[QuestionOption], survey: NextIdentificationSurvey): HtmlElement =
    select(
      cls := "select-input",
      option(value := "", "Selecciona una opción", disabled := true, selected := true),
      options.sortBy(_.displayOrder).map: opt =>
        option(value := opt.id.asString, opt.text.value),
      onChange.mapToValue --> { v =>
        options.find(_.id.asString == v).foreach: opt =>
          answerValue.set(Some(AnswerValue.SingleChoice(opt.id)))
          validateAnswer(survey)
      }
    )

  private def renderCheckbox(options: List[QuestionOption], survey: NextIdentificationSurvey): HtmlElement =
    val selectedIds: Var[Set[String]] = Var(Set.empty)
    div(
      cls := "checkbox-group",
      options.sortBy(_.displayOrder).map: opt =>
        label(
          cls := "checkbox-option",
          input(
            typ := "checkbox",
            value := opt.id.asString,
            onChange.mapToChecked --> { checked =>
              val current = selectedIds.now()
              val updated =
                if checked then current + opt.id.asString
                else current - opt.id.asString
              selectedIds.set(updated)
              if updated.nonEmpty then
                val optionIds = options.filter(o => updated.contains(o.id.asString)).map(_.id).toSet
                answerValue.set(Some(AnswerValue.MultipleChoice(optionIds)))
              else answerValue.set(None)
              validateAnswer(survey)
            }
          ),
          span(cls := "checkbox-label", opt.text.value)
        )
    )

  private def renderRating(survey: NextIdentificationSurvey): HtmlElement =
    // TODO: Implement rating component (stars or slider)
    div(cls := "rating-input", "Rating input - TODO")

  private def renderNumeric(survey: NextIdentificationSurvey): HtmlElement =
    input(
      cls := "numeric-input",
      typ := "number",
      onInput.mapToValue --> { v =>
        if v.nonEmpty then
          try
            val num = BigDecimal(v)
            answerValue.set(Some(AnswerValue.Numeric(num)))
          catch case _: Exception => answerValue.set(None)
        else answerValue.set(None)
      },
      onBlur --> { _ => validateAnswer(survey) }
    )

  private def renderDate(survey: NextIdentificationSurvey): HtmlElement =
    input(
      cls := "date-input",
      typ := "date",
      onInput.mapToValue --> { v =>
        if v.nonEmpty then
          try
            val date = java.time.LocalDate.parse(v)
            answerValue.set(Some(AnswerValue.DateValue(date)))
          catch case _: Exception => answerValue.set(None)
        else answerValue.set(None)
      },
      onBlur --> { _ => validateAnswer(survey) }
    )

  private def submitAnswer(survey: NextIdentificationSurvey): Unit =
    answerValue.now().foreach: answer =>
      isSubmitting.set(true)
      serverError.set(None)

      val apiCall = survey.surveyType match
        case IdentificationSurveyType.Email     => ApiClient.answerEmail(answer)
        case IdentificationSurveyType.Profiling => ApiClient.answerProfiling(answer)
        case IdentificationSurveyType.Location  => ApiClient.answerLocation(answer)

      Runtime.run:
        for
          result <- apiCall
          _ <- ZIO.succeed:
            result match
              case Right(SurveyResponse.Survey(nextSurvey)) =>
                // Success: clear state and show next question
                isSubmitting.set(false)
                answerValue.set(None)
                textInput.set("")
                validationError.set(None)
                isTouched.set(false)
                AppState.setCurrentSurvey(nextSurvey)
              case Right(SurveyResponse.Step(nextStep)) =>
                // Success: identification complete, navigate to next phase
                isSubmitting.set(false)
                answerValue.set(None)
                textInput.set("")
                validationError.set(None)
                isTouched.set(false)
                AppState.clearCurrentSurvey()
                AppState.setPhase(nextStep.phase)
                Router.syncWithPhase(nextStep.phase)
              case Left(error) =>
                isSubmitting.set(false)
                serverError.set(Some(errorToMessage(error)))
        yield ()

  private def errorToMessage(error: whitelabel.captal.endpoints.ApiError): String =
    import whitelabel.captal.endpoints.ApiError.*
    error match
      case SessionMissing        => "Session missing"
      case SessionInvalid(_)     => "Session invalid"
      case SessionExpired        => "Session expired"
      case InvalidEmail(_)       => I18nClient.current.question.invalidEmail
      case InvalidEmailFormat(_) => I18nClient.current.question.invalidEmail
      case InternalError(msg)    => msg
      case _                     => I18nClient.current.error.generic
end IdentificationQuestionView
