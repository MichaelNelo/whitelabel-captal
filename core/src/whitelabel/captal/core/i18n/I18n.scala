package whitelabel.captal.core.i18n

import io.taig.babel.generic.auto.*
import io.taig.babel.{Decoder, Encoder}

final case class I18n(
    welcome: I18n.Welcome,
    ready: I18n.Ready,
    loading: I18n.Loading,
    error: I18n.Error,
    question: I18n.Question,
    video: I18n.Video)

object I18n:
  final case class Welcome(
      title: String,
      subtitle: String,
      steps: Welcome.Steps,
      button: Welcome.Button,
      selectLanguage: String)

  object Welcome:
    final case class Steps(step1: String, step2: String, step3: String)
    object Steps:
      given Decoder[Steps] = deriveDecoder[Steps]
      given Encoder[Steps] = deriveEncoder[Steps]

    final case class Button(start: String, connecting: String)
    object Button:
      given Decoder[Button] = deriveDecoder[Button]
      given Encoder[Button] = deriveEncoder[Button]

    given Decoder[Welcome] = deriveDecoder[Welcome]
    given Encoder[Welcome] = deriveEncoder[Welcome]

  final case class Ready(title: String, subtitle: String, resetButton: String)
  object Ready:
    given Decoder[Ready] = deriveDecoder[Ready]
    given Encoder[Ready] = deriveEncoder[Ready]

  final case class Loading(message: String)
  object Loading:
    given Decoder[Loading] = deriveDecoder[Loading]
    given Encoder[Loading] = deriveEncoder[Loading]

  final case class Error(title: String, retry: String, generic: String)
  object Error:
    given Decoder[Error] = deriveDecoder[Error]
    given Encoder[Error] = deriveEncoder[Error]

  final case class Question(
      submit: String,
      next: String,
      required: String,
      invalidEmail: String,
      invalidUrl: String,
      invalidPattern: String,
      minLength: String,
      maxLength: String,
      minSelections: String,
      maxSelections: String,
      invalidOption: String,
      ratingOutOfRange: String,
      numericOutOfRange: String,
      dateOutOfRange: String,
      invalidAnswer: String)
  object Question:
    given Decoder[Question] = deriveDecoder[Question]
    given Encoder[Question] = deriveEncoder[Question]

  final case class Video(
      pageTitle: String,
      continueIn: String,
      watchComplete: String,
      markWatched: String,
      loading: String,
      noVideoAvailable: String,
      payAttention: String)
  object Video:
    given Decoder[Video] = deriveDecoder[Video]
    given Encoder[Video] = deriveEncoder[Video]

  given decoder: Decoder[I18n] = deriveDecoder[I18n]
  given encoder: Encoder[I18n] = deriveEncoder[I18n]
end I18n
