package whitelabel.captal.core.user

import whitelabel.captal.core.survey.question.FullyQualifiedQuestionId
import whitelabel.captal.core.{survey, video}

enum State:
  case Guest
  case WithEmail(email: Email)
  case AnsweringQuestion(surveyId: survey.Id, questionId: survey.question.Id)
  case WatchingVideo(videoId: video.Id)
  case AnsweringVideoSurvey(
      advertiserId: survey.AdvertiserId,
      surveyId: survey.Id,
      questionId: survey.question.Id)
  case Ready(
      redirectUrl: String,
      watchedVideoId: Option[video.Id],
      answeredQuestionIds: List[FullyQualifiedQuestionId])
  case Authorized(
      redirectUrl: String,
      watchedVideoId: Option[video.Id],
      answeredQuestionIds: List[FullyQualifiedQuestionId])
