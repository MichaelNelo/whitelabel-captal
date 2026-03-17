package whitelabel.captal.client.views

import com.raquo.laminar.api.L.*
import whitelabel.captal.client.i18n.I18nClient
import whitelabel.captal.client.{ApiClient, AppState, Router, Runtime}
import whitelabel.captal.core.Op
import whitelabel.captal.core.application.commands.NextAdvertiserSurvey
import whitelabel.captal.core.application.Phase
import whitelabel.captal.core.survey.Error as SurveyError
import whitelabel.captal.core.survey.question.{
  AnswerValue,
  QuestionOption,
  QuestionType,
  TextRule,
  ops as questionOps
}
import whitelabel.captal.endpoints.AdvertiserSurveyResponse
import scala.concurrent.ExecutionContext.Implicits.global

object AdvertiserVideoSurveyView:
  private val answerValue: Var[Option[AnswerValue]] = Var(None)
  private val textInput: Var[String] = Var("")
  private val isSubmitting: Var[Boolean] = Var(false)
  private val validationError: Var[Option[String]] = Var(None)
  private val serverError: Var[Option[String]] = Var(None)
  private val isTouched: Var[Boolean] = Var(false)

  def render: HtmlElement = Layout(
    isLoading = AppState.currentAdvertiserSurvey.map(_.isEmpty),
    content = div(
      cls := "question-view",
      child <--
        AppState
          .currentAdvertiserSurvey
          .map:
            case None =>
              div()
            case Some(survey) =>
              questionsContent(survey)
      ,
      onMountCallback { _ =>
        loadQuestion()
      }
    ),
    footer = div(
      child.maybe <-- serverError.signal.map(_.map(msg => div(cls := "server-error", msg))),
      child <--
        AppState
          .currentAdvertiserSurvey
          .map:
            case None =>
              div()
            case Some(survey) =>
              button(
                cls := "question-submit-button",
                disabled <--
                  isSubmitting
                    .signal
                    .combineWith(answerValue.signal, validationError.signal)
                    .map:
                      case (submitting, answer, validErr) =>
                        submitting || answer.isEmpty || validErr.isDefined,
                child.text <--
                  isSubmitting
                    .signal
                    .combineWith(I18nClient.i18n)
                    .map:
                      case (true, _) =>
                        "..."
                      case (false, i18n) =>
                        i18n.question.submit
                ,
                onClick --> { _ =>
                  submitAnswer(survey)
                }
              )
    )
  )

  private def questionsContent(survey: NextAdvertiserSurvey): HtmlElement = div(
    cls := "question-container",
    div(cls := "questions-list", questionCard(survey)))

  private def loadQuestion(): Unit = Runtime.run:
    ApiClient.getNextAdvertiserSurvey().map:
      case Right(AdvertiserSurveyResponse.Survey(survey)) =>
        AppState.setCurrentAdvertiserSurvey(survey)
      case Right(AdvertiserSurveyResponse.Step(nextStep)) =>
        AppState.setPhase(nextStep.phase)
        Router.syncWithPhase(nextStep.phase)
      case Left(_) =>
        ()

  private def questionCard(survey: NextAdvertiserSurvey): HtmlElement =
    val cardStateSignal = isTouched
      .signal
      .combineWith(validationError.signal, answerValue.signal)
      .map:
        case (false, _, _) =>
          "pristine"
        case (true, Some(_), _) =>
          "error"
        case (true, None, Some(_)) =>
          "valid"
        case (true, None, None) =>
          "pristine"

    div(
      cls := "question-card",
      cls <--
        cardStateSignal.map:
          case "error" =>
            "card-error"
          case "valid" =>
            "card-valid"
          case _ =>
            ""
      ,
      h2(cls := "question-title", survey.question.text.value),
      survey.question.description.map(desc => p(cls := "question-description", desc.value)),
      div(cls := "question-input-area", renderQuestionType(survey.question.questionType, survey)),
      child.maybe <-- validationError.signal.map(_.map(msg => div(cls := "validation-error", msg)))
    )
  end questionCard

  private def validateAnswer(survey: NextAdvertiserSurvey): Unit =
    isTouched.set(true)
    val q = survey.question
    answerValue.now() match
      case None =>
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
            validationError.set(Some(I18nClient.current.error.generic))

  private def renderQuestionType(qt: QuestionType, survey: NextAdvertiserSurvey): HtmlElement =
    qt match
      case QuestionType.Input(rules) =>
        renderInput(rules, survey)
      case QuestionType.Radio(options) =>
        renderRadio(options, survey)
      case QuestionType.Select(options) =>
        renderSelect(options, survey)
      case QuestionType.Checkbox(options, rules) =>
        renderCheckbox(options, survey)
      case QuestionType.Rating(rules) =>
        div(cls := "rating-input", "Rating input - TODO")
      case QuestionType.Numeric(rules) =>
        renderNumeric(survey)
      case QuestionType.Date(rules) =>
        renderDate(survey)

  private def renderInput(rules: List[TextRule], survey: NextAdvertiserSurvey): HtmlElement =
    input(
      cls := "text-input",
      typ := "text",
      placeholder := survey.question.placeholder.map(_.value).getOrElse(""),
      controlled(
        value <-- textInput.signal,
        onInput.mapToValue --> { v =>
          textInput.set(v)
          if v.nonEmpty then
            answerValue.set(Some(AnswerValue.Text(v)))
          else
            answerValue.set(None)
        }
      ),
      onBlur --> { _ =>
        validateAnswer(survey)
      }
    )

  private def renderRadio(
      options: List[QuestionOption],
      survey: NextAdvertiserSurvey): HtmlElement = div(
    cls := "radio-group",
    options
      .sortBy(_.displayOrder)
      .map: opt =>
        label(
          cls := "radio-option",
          input(
            typ      := "radio",
            nameAttr := "advertiser-question-radio",
            value    := opt.id.asString,
            onChange.mapToValue --> { _ =>
              answerValue.set(Some(AnswerValue.SingleChoice(opt.id)))
              validateAnswer(survey)
            }
          ),
          span(cls := "radio-label", opt.text.value)
        )
  )

  private def renderSelect(
      options: List[QuestionOption],
      survey: NextAdvertiserSurvey): HtmlElement = select(
    cls := "select-input",
    option(value := "", "Selecciona una opción", disabled := true, selected := true),
    options
      .sortBy(_.displayOrder)
      .map: opt =>
        option(value := opt.id.asString, opt.text.value),
    onChange.mapToValue --> { v =>
      options
        .find(_.id.asString == v)
        .foreach: opt =>
          answerValue.set(Some(AnswerValue.SingleChoice(opt.id)))
          validateAnswer(survey)
    }
  )

  private def renderCheckbox(
      options: List[QuestionOption],
      survey: NextAdvertiserSurvey): HtmlElement =
    val selectedIds: Var[Set[String]] = Var(Set.empty)
    div(
      cls := "checkbox-group",
      options
        .sortBy(_.displayOrder)
        .map: opt =>
          label(
            cls := "checkbox-option",
            input(
              typ   := "checkbox",
              value := opt.id.asString,
              onChange.mapToChecked --> { checked =>
                val current = selectedIds.now()
                val updated =
                  if checked then current + opt.id.asString
                  else current - opt.id.asString
                selectedIds.set(updated)
                if updated.nonEmpty then
                  val optionIds =
                    options.filter(o => updated.contains(o.id.asString)).map(_.id).toSet
                  answerValue.set(Some(AnswerValue.MultipleChoice(optionIds)))
                else
                  answerValue.set(None)
                validateAnswer(survey)
              }
            ),
            span(cls := "checkbox-label", opt.text.value)
          )
    )

  private def renderNumeric(survey: NextAdvertiserSurvey): HtmlElement = input(
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

  private def renderDate(survey: NextAdvertiserSurvey): HtmlElement = input(
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

  private def submitAnswer(survey: NextAdvertiserSurvey): Unit = answerValue
    .now()
    .foreach: answer =>
      isSubmitting.set(true)
      serverError.set(None)

      Runtime.run:
        ApiClient.answerAdvertiser(answer).map:
          case Right(AdvertiserSurveyResponse.Survey(nextSurvey)) =>
            isSubmitting.set(false)
            answerValue.set(None)
            textInput.set("")
            validationError.set(None)
            isTouched.set(false)
            AppState.setCurrentAdvertiserSurvey(nextSurvey)
          case Right(AdvertiserSurveyResponse.Step(nextStep)) =>
            AppState.setNavigating(true)
            isSubmitting.set(false)
            answerValue.set(None)
            textInput.set("")
            validationError.set(None)
            isTouched.set(false)
            AppState.clearCurrentAdvertiserSurvey()
            AppState.setPhase(nextStep.phase)
            Router.syncWithPhase(nextStep.phase)
            AppState.setNavigating(false)
          case Left(error) =>
            isSubmitting.set(false)
            serverError.set(Some(I18nClient.current.error.generic))
end AdvertiserVideoSurveyView
