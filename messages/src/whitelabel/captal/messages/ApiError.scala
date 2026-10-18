package whitelabel.captal.messages

import cats.data.NonEmptyChain
import io.circe.{Decoder, Encoder}
import whitelabel.captal.core.application.Error as AppError
import whitelabel.captal.core.{survey, user, video}

enum ApiError:
  // Session errors
  case SessionMissing
  case SessionInvalid(reason: String)
  case SessionExpired

  // Phase errors
  case WrongPhase(current: String, expected: List[String])

  // Application errors
  case NoSurveyAssigned
  case UserNotIdentified
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

  // Video errors
  case VideoNotFound(videoId: String)
  case NoVideoAvailable

  // Location errors
  case LocationNotFound(slug: String)

  // Internal
  case InternalError(message: String)
end ApiError

object ApiError:
  def fromAppErrors(errors: NonEmptyChain[AppError]): ApiError = fromAppError(errors.head)

  def fromAppError(err: AppError): ApiError =
    err match
      case AppError.NoSurveyAssigned =>
        ApiError.NoSurveyAssigned
      case AppError.UserNotIdentified =>
        ApiError.UserNotIdentified
      case AppError.InvalidEmailFormat(value) =>
        ApiError.InvalidEmailFormat(value)
      case AppError.Survey(errors) =>
        fromSurveyError(errors.head)
      case AppError.User(errors) =>
        fromUserError(errors.head)
      case AppError.Video(errors) =>
        fromVideoError(errors.head)

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

  private def fromVideoError(err: video.Error): ApiError =
    err match
      case video.Error.VideoNotFound(videoId) =>
        ApiError.VideoNotFound(videoId.asString)
      case video.Error.NoVideoAvailable =>
        ApiError.NoVideoAvailable

  def fromThrowable(cause: Throwable): ApiError = ApiError.InternalError(
    Option(cause.getMessage).getOrElse("Unknown error"))

  /** Derived, both directions, which is the point rather than a tidying.
    *
    * These were written by hand and separately: an `Encoder.instance` matching on the ADT, and a
    * `Decoder.instance` matching on a `String` with a catch-all. The compiler forces the first to
    * cover every variant — a non-exhaustive match on an enum is an error — and cannot say anything
    * about the second, because a match on a string with a default is always exhaustive. So the
    * encoder grew with the type thirty-two times and the decoder stayed at nine, and the catch-all
    * turned the gap into a plausible value instead of a failure:
    *
    * case other => Right(InternalError(s"Unknown error type: $other"))
    *
    * Twenty-two variants — every validation error the API can answer with — came back to the
    * browser as `InternalError("Unknown error type: text_too_short")`, and `ErrorHandler` sends
    * that to the error screen. A guest whose answer the server rejected for a reason the client
    * could have shown got dropped out of the flow instead.
    *
    * Derivation makes that unrepresentable: there is one description of the shape and both
    * directions come from it. The wire format changes with it — the hand-written pair wrote
    * `{"error":"wrong_phase","data":{…}}` and this writes circe's derived shape — which is what
    * `WireContractSuite` pins against the zio-schema side.
    */
  given Encoder[ApiError] = Encoder.AsObject.derived
  given Decoder[ApiError] = Decoder.derived
end ApiError
