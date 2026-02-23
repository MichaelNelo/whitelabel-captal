package whitelabel.captal.api

import cats.data.NonEmptyChain
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}
import sttp.tapir.Schema
import whitelabel.captal.core.application.Error as AppError
import whitelabel.captal.core.{survey, user}

enum ApiError:
  // Session errors
  case SessionMissing
  case SessionInvalid(reason: String)
  case SessionExpired

  // Application errors
  case NoSurveyAssigned
  case SurveyNotFound(surveyId: String)
  case UserNotFound(userId: String)
  case QuestionNotInExpectedState(surveyId: String, questionId: String)
  case InvalidEmailFormat(value: String)

  // Survey validation errors
  case RequiredAnswerMissing(questionId: String)
  case InvalidOptionSelected(questionId: String, optionId: String)
  case InvalidOptionsSelected(questionId: String, optionIds: List[String])
  case TooFewSelections(questionId: String, min: Int, actual: Int)
  case TooManySelections(questionId: String, max: Int, actual: Int)
  case TextTooShort(questionId: String, minLength: Int, actual: Int)
  case TextTooLong(questionId: String, maxLength: Int, actual: Int)
  case InvalidPattern(questionId: String, pattern: String)
  case InvalidEmail(questionId: String)
  case InvalidUrl(questionId: String)
  case RatingOutOfRange(questionId: String, min: Float, max: Float, actual: Float)
  case NumericOutOfRange(questionId: String, min: String, max: String, actual: String)
  case DateOutOfRange(questionId: String, min: String, max: String, actual: String)
  case IncompatibleAnswerType(questionId: String, expectedType: String)
  case QuestionAlreadyAnswered(questionId: String)
  case QuestionNotFound(questionId: String)
  case HierarchyViolation(questionId: String, expectedLevel: String)

  // User errors
  case EmailAlreadyRegistered(userId: String)
  case UserSessionExpired(sessionId: String)
  case NoPendingQuestions(userId: String)
  case QuestionNotPending(userId: String, questionId: String)

  // Internal
  case InternalError(message: String)

object ApiError:
  def fromAppErrors(errors: NonEmptyChain[AppError]): ApiError =
    fromAppError(errors.head)

  def fromAppError(err: AppError): ApiError =
    err match
      case AppError.NoSurveyAssigned =>
        ApiError.NoSurveyAssigned
      case AppError.SurveyNotFound(surveyId) =>
        ApiError.SurveyNotFound(surveyId.asString)
      case AppError.UserNotFound(userId) =>
        ApiError.UserNotFound(userId.asString)
      case AppError.QuestionNotInExpectedState(surveyId, questionId) =>
        ApiError.QuestionNotInExpectedState(surveyId.asString, questionId.asString)
      case AppError.InvalidEmailFormat(value) =>
        ApiError.InvalidEmailFormat(value)
      case AppError.Survey(errors) =>
        fromSurveyError(errors.head)
      case AppError.User(errors) =>
        fromUserError(errors.head)

  private def fromSurveyError(err: survey.Error): ApiError =
    err match
      case survey.Error.RequiredAnswerMissing(qId) =>
        ApiError.RequiredAnswerMissing(qId.asString)
      case survey.Error.InvalidOptionSelected(qId, optId) =>
        ApiError.InvalidOptionSelected(qId.asString, optId.asString)
      case survey.Error.InvalidOptionsSelected(qId, optIds) =>
        ApiError.InvalidOptionsSelected(qId.asString, optIds.map(_.asString).toList)
      case survey.Error.TooFewSelections(qId, min, actual) =>
        ApiError.TooFewSelections(qId.asString, min, actual)
      case survey.Error.TooManySelections(qId, max, actual) =>
        ApiError.TooManySelections(qId.asString, max, actual)
      case survey.Error.TextTooShort(qId, minLen, actual) =>
        ApiError.TextTooShort(qId.asString, minLen, actual)
      case survey.Error.TextTooLong(qId, maxLen, actual) =>
        ApiError.TextTooLong(qId.asString, maxLen, actual)
      case survey.Error.InvalidPattern(qId, pattern) =>
        ApiError.InvalidPattern(qId.asString, pattern)
      case survey.Error.InvalidEmail(qId) =>
        ApiError.InvalidEmail(qId.asString)
      case survey.Error.InvalidUrl(qId) =>
        ApiError.InvalidUrl(qId.asString)
      case survey.Error.RatingOutOfRange(qId, min, max, actual) =>
        ApiError.RatingOutOfRange(qId.asString, min, max, actual)
      case survey.Error.NumericOutOfRange(qId, min, max, actual) =>
        ApiError.NumericOutOfRange(qId.asString, min.toString, max.toString, actual.toString)
      case survey.Error.DateOutOfRange(qId, min, max, actual) =>
        ApiError.DateOutOfRange(qId.asString, min.toString, max.toString, actual.toString)
      case survey.Error.IncompatibleAnswerType(qId, expected) =>
        ApiError.IncompatibleAnswerType(qId.asString, expected.toString)
      case survey.Error.QuestionAlreadyAnswered(qId) =>
        ApiError.QuestionAlreadyAnswered(qId.asString)
      case survey.Error.QuestionNotFound(qId) =>
        ApiError.QuestionNotFound(qId.asString)
      case survey.Error.HierarchyViolation(qId, level) =>
        ApiError.HierarchyViolation(qId.asString, level.toString)

  private def fromUserError(err: user.Error): ApiError =
    err match
      case user.Error.EmailAlreadyRegistered(userId) =>
        ApiError.EmailAlreadyRegistered(userId.asString)
      case user.Error.InvalidEmailFormat(email) =>
        ApiError.InvalidEmailFormat(email)
      case user.Error.SessionExpired(sessionId) =>
        ApiError.UserSessionExpired(sessionId.asString)
      case user.Error.NoPendingQuestions(userId) =>
        ApiError.NoPendingQuestions(userId.asString)
      case user.Error.QuestionNotPending(userId, questionId) =>
        ApiError.QuestionNotPending(userId.asString, questionId)

  given Encoder[ApiError] = Encoder.instance: err =>
    val (errorType, data) = err match
      case SessionMissing =>
        ("session_missing", Json.obj())
      case SessionInvalid(reason) =>
        ("session_invalid", Json.obj("reason" -> reason.asJson))
      case SessionExpired =>
        ("session_expired", Json.obj())
      case NoSurveyAssigned =>
        ("no_survey_assigned", Json.obj())
      case SurveyNotFound(id) =>
        ("survey_not_found", Json.obj("surveyId" -> id.asJson))
      case UserNotFound(id) =>
        ("user_not_found", Json.obj("userId" -> id.asJson))
      case QuestionNotInExpectedState(sId, qId) =>
        ("question_not_in_expected_state", Json.obj("surveyId" -> sId.asJson, "questionId" -> qId.asJson))
      case InvalidEmailFormat(value) =>
        ("invalid_email_format", Json.obj("value" -> value.asJson))
      case RequiredAnswerMissing(qId) =>
        ("required_answer_missing", Json.obj("questionId" -> qId.asJson))
      case InvalidOptionSelected(qId, optId) =>
        ("invalid_option_selected", Json.obj("questionId" -> qId.asJson, "optionId" -> optId.asJson))
      case InvalidOptionsSelected(qId, optIds) =>
        ("invalid_options_selected", Json.obj("questionId" -> qId.asJson, "optionIds" -> optIds.asJson))
      case TooFewSelections(qId, min, actual) =>
        ("too_few_selections", Json.obj("questionId" -> qId.asJson, "min" -> min.asJson, "actual" -> actual.asJson))
      case TooManySelections(qId, max, actual) =>
        ("too_many_selections", Json.obj("questionId" -> qId.asJson, "max" -> max.asJson, "actual" -> actual.asJson))
      case TextTooShort(qId, minLen, actual) =>
        ("text_too_short", Json.obj("questionId" -> qId.asJson, "minLength" -> minLen.asJson, "actual" -> actual.asJson))
      case TextTooLong(qId, maxLen, actual) =>
        ("text_too_long", Json.obj("questionId" -> qId.asJson, "maxLength" -> maxLen.asJson, "actual" -> actual.asJson))
      case InvalidPattern(qId, pattern) =>
        ("invalid_pattern", Json.obj("questionId" -> qId.asJson, "pattern" -> pattern.asJson))
      case InvalidEmail(qId) =>
        ("invalid_email", Json.obj("questionId" -> qId.asJson))
      case InvalidUrl(qId) =>
        ("invalid_url", Json.obj("questionId" -> qId.asJson))
      case RatingOutOfRange(qId, min, max, actual) =>
        ("rating_out_of_range", Json.obj("questionId" -> qId.asJson, "min" -> min.asJson, "max" -> max.asJson, "actual" -> actual.asJson))
      case NumericOutOfRange(qId, min, max, actual) =>
        ("numeric_out_of_range", Json.obj("questionId" -> qId.asJson, "min" -> min.asJson, "max" -> max.asJson, "actual" -> actual.asJson))
      case DateOutOfRange(qId, min, max, actual) =>
        ("date_out_of_range", Json.obj("questionId" -> qId.asJson, "min" -> min.asJson, "max" -> max.asJson, "actual" -> actual.asJson))
      case IncompatibleAnswerType(qId, expected) =>
        ("incompatible_answer_type", Json.obj("questionId" -> qId.asJson, "expectedType" -> expected.asJson))
      case QuestionAlreadyAnswered(qId) =>
        ("question_already_answered", Json.obj("questionId" -> qId.asJson))
      case QuestionNotFound(qId) =>
        ("question_not_found", Json.obj("questionId" -> qId.asJson))
      case HierarchyViolation(qId, level) =>
        ("hierarchy_violation", Json.obj("questionId" -> qId.asJson, "expectedLevel" -> level.asJson))
      case EmailAlreadyRegistered(userId) =>
        ("email_already_registered", Json.obj("userId" -> userId.asJson))
      case UserSessionExpired(sessionId) =>
        ("user_session_expired", Json.obj("sessionId" -> sessionId.asJson))
      case NoPendingQuestions(userId) =>
        ("no_pending_questions", Json.obj("userId" -> userId.asJson))
      case QuestionNotPending(userId, qId) =>
        ("question_not_pending", Json.obj("userId" -> userId.asJson, "questionId" -> qId.asJson))
      case InternalError(message) =>
        ("internal_error", Json.obj("message" -> message.asJson))
    Json.obj("error" -> errorType.asJson, "data" -> data)

  given Decoder[ApiError] = Decoder.instance: cursor =>
    for
      errorType <- cursor.downField("error").as[String]
      data = cursor.downField("data")
      result <- errorType match
        case "session_missing" =>
          Right(SessionMissing)
        case "session_invalid" =>
          data.downField("reason").as[String].map(SessionInvalid(_))
        case "session_expired" =>
          Right(SessionExpired)
        case "no_survey_assigned" =>
          Right(NoSurveyAssigned)
        case "survey_not_found" =>
          data.downField("surveyId").as[String].map(SurveyNotFound(_))
        case "user_not_found" =>
          data.downField("userId").as[String].map(UserNotFound(_))
        case "invalid_email_format" =>
          data.downField("value").as[String].map(InvalidEmailFormat(_))
        case "internal_error" =>
          data.downField("message").as[String].map(InternalError(_))
        case other =>
          Right(InternalError(s"Unknown error type: $other"))
    yield result

  given Schema[ApiError] = Schema.string
end ApiError
