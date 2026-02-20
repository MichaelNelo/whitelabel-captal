package whitelabel.captal.core.survey

import java.time.Instant

import cats.syntax.functor.*
import cats.syntax.parallel.*
import whitelabel.captal.core.Op
import whitelabel.captal.core.survey.question.*
import whitelabel.captal.core.survey.question.Event as QuestionEvent
import whitelabel.captal.core.survey.Event as SurveyEvent
import whitelabel.captal.core.user.{State as UserState, User}

object ops:
  type SurveyOp[A] = Op[SurveyEvent, Error, A]

  /** Answer an email question */
  def answerEmail(
      user: User[UserState.AnsweringQuestion],
      survey: Survey[State.WithEmailQuestion],
      value: AnswerValue,
      now: Instant): SurveyOp[QuestionAnswer] =
    val q = survey.state.question
    for
      _ <- validate(q.id, q.commonRules, q.questionType, value)
      answer        = QuestionAnswer(q.id, value, q.pointsAwarded, now)
      questionEvent = QuestionEvent.EmailQuestionAnswered(
        userId = user.id,
        sessionId = user.state.sessionId,
        surveyId = survey.id,
        questionId = q.id,
        answer = answer,
        locale = user.state.locale,
        occurredAt = now)
      result <- Op.emit(SurveyEvent.QuestionAnswered(survey.id, questionEvent), answer)
    yield result

  /** Answer a profiling question */
  def answerProfiling(
      user: User[UserState.AnsweringQuestion],
      survey: Survey[State.WithProfilingQuestion],
      value: AnswerValue,
      now: Instant): SurveyOp[QuestionAnswer] =
    val q = survey.state.question
    for
      _ <- validate(q.id, q.commonRules, q.questionType, value)
      answer        = QuestionAnswer(q.id, value, q.pointsAwarded, now)
      questionEvent = QuestionEvent.ProfilingQuestionAnswered(
        userId = user.id,
        sessionId = user.state.sessionId,
        surveyId = survey.id,
        questionId = q.id,
        answer = answer,
        locale = user.state.locale,
        occurredAt = now)
      result <- Op.emit(SurveyEvent.QuestionAnswered(survey.id, questionEvent), answer)
    yield result

  /** Answer a location question */
  def answerLocation(
      user: User[UserState.AnsweringQuestion],
      survey: Survey[State.WithLocationQuestion],
      value: AnswerValue,
      now: Instant): SurveyOp[QuestionAnswer] =
    val q = survey.state.question
    for
      _ <- validate(q.id, q.commonRules, q.questionType, value)
      answer        = QuestionAnswer(q.id, value, q.pointsAwarded, now)
      questionEvent = QuestionEvent.LocationQuestionAnswered(
        userId = user.id,
        sessionId = user.state.sessionId,
        surveyId = survey.id,
        questionId = q.id,
        hierarchyLevel = survey.state.hierarchyLevel,
        answer = answer,
        locale = user.state.locale,
        occurredAt = now
      )
      result <- Op.emit(SurveyEvent.QuestionAnswered(survey.id, questionEvent), answer)
    yield result

  /** Answer an advertiser question */
  def answerAdvertiser(
      user: User[UserState.AnsweringQuestion],
      survey: Survey[State.WithAdvertiserQuestion],
      value: AnswerValue,
      now: Instant): SurveyOp[QuestionAnswer] =
    val q = survey.state.question
    for
      _ <- validate(q.id, q.commonRules, q.questionType, value)
      answer        = QuestionAnswer(q.id, value, q.pointsAwarded, now)
      questionEvent = QuestionEvent.AdvertiserQuestionAnswered(
        userId = user.id,
        sessionId = user.state.sessionId,
        surveyId = survey.id,
        advertiserId = survey.state.advertiserId,
        questionId = q.id,
        answer = answer,
        locale = user.state.locale,
        occurredAt = now
      )
      result <- Op.emit(SurveyEvent.QuestionAnswered(survey.id, questionEvent), answer)
    yield result

  /** Validate answer against common and type-specific rules */
  private def validate(
      questionId: QuestionId,
      commonRules: List[CommonRule],
      questionType: QuestionType,
      value: AnswerValue): SurveyOp[Unit] =
    for
      _ <- validateCommonRules(questionId, commonRules, value)
      _ <- validateTypeRules(questionId, questionType, value)
    yield ()

  /** Validate common rules (Required, etc.) */
  private def validateCommonRules(
      questionId: QuestionId,
      rules: List[CommonRule],
      value: AnswerValue): SurveyOp[Unit] =
    val validations: List[SurveyOp[Unit]] = rules.map:
      case CommonRule.Required =>
        val isEmpty =
          value match
            case AnswerValue.Text(v) =>
              v.trim.isEmpty
            case AnswerValue.MultipleChoice(ids) =>
              ids.isEmpty
            case _ =>
              false
        Op.failIf(isEmpty, Error.RequiredAnswerMissing(questionId))
    if validations.isEmpty then
      Op.pure(())
    else
      validations.parSequence.void

  /** Validate type-specific rules */
  private def validateTypeRules(
      questionId: QuestionId,
      questionType: QuestionType,
      value: AnswerValue): SurveyOp[Unit] =
    (questionType, value) match
      case (QuestionType.Radio(options), AnswerValue.SingleChoice(optionId)) =>
        Op.failUnless(
          options.exists(_.id == optionId),
          Error.InvalidOptionSelected(questionId, optionId))

      case (QuestionType.Select(options), AnswerValue.SingleChoice(optionId)) =>
        Op.failUnless(
          options.exists(_.id == optionId),
          Error.InvalidOptionSelected(questionId, optionId))

      case (QuestionType.Checkbox(options, rules), AnswerValue.MultipleChoice(optionIds)) =>
        val invalidIds                       = optionIds.filterNot(id => options.exists(_.id == id))
        val optionValidation: SurveyOp[Unit] = Op.failIf(
          invalidIds.nonEmpty,
          Error.InvalidOptionsSelected(questionId, invalidIds))
        val ruleValidations: List[SurveyOp[Unit]] = rules.map:
          case SelectionRule.MinSelections(min) =>
            Op.failIf(optionIds.size < min, Error.TooFewSelections(questionId, min, optionIds.size))
          case SelectionRule.MaxSelections(max) =>
            Op.failIf(
              optionIds.size > max,
              Error.TooManySelections(questionId, max, optionIds.size))
        (optionValidation :: ruleValidations).parSequence.void

      case (QuestionType.Input(rules), AnswerValue.Text(text)) =>
        val validations: List[SurveyOp[Unit]] = rules.map:
          case TextRule.MinLength(min) =>
            Op.failIf(text.length < min, Error.TextTooShort(questionId, min, text.length))
          case TextRule.MaxLength(max) =>
            Op.failIf(text.length > max, Error.TextTooLong(questionId, max, text.length))
          case TextRule.Pattern(regex) =>
            Op.failUnless(text.matches(regex), Error.InvalidPattern(questionId, regex))
          case TextRule.Email =>
            val emailRegex = "^[\\w.-]+@[\\w.-]+\\.[a-zA-Z]{2,}$"
            Op.failUnless(text.matches(emailRegex), Error.InvalidEmail(questionId))
          case TextRule.Url =>
            val urlRegex = "^https?://[\\w.-]+(?:/[\\w./-]*)?$"
            Op.failUnless(text.matches(urlRegex), Error.InvalidUrl(questionId))

        if validations.isEmpty then
          Op.pure(())
        else
          validations.parSequence.void

      case (QuestionType.Rating(rules), AnswerValue.Rating(v)) =>
        val validations: List[SurveyOp[Unit]] = rules.map:
          case RangeRule.Min(min) =>
            Op.failIf(v < min, Error.RatingOutOfRange(questionId, min, min, v))
          case RangeRule.Max(max) =>
            Op.failIf(v > max, Error.RatingOutOfRange(questionId, max, max, v))
        if validations.isEmpty then
          Op.pure(())
        else
          validations.parSequence.void

      case (QuestionType.Numeric(rules), AnswerValue.Numeric(v)) =>
        val validations: List[SurveyOp[Unit]] = rules.map:
          case RangeRule.Min(min) =>
            Op.failIf(v < min, Error.NumericOutOfRange(questionId, min, min, v))
          case RangeRule.Max(max) =>
            Op.failIf(v > max, Error.NumericOutOfRange(questionId, max, max, v))
        if validations.isEmpty then
          Op.pure(())
        else
          validations.parSequence.void

      case (QuestionType.Date(rules), AnswerValue.DateValue(d)) =>
        val validations: List[SurveyOp[Unit]] = rules.map:
          case RangeRule.Min(min) =>
            Op.failIf(d.isBefore(min), Error.DateOutOfRange(questionId, min, min, d))
          case RangeRule.Max(max) =>
            Op.failIf(d.isAfter(max), Error.DateOutOfRange(questionId, max, max, d))
        if validations.isEmpty then
          Op.pure(())
        else
          validations.parSequence.void

      case _ =>
        Op.fail(Error.IncompatibleAnswerType(questionId, questionType))
