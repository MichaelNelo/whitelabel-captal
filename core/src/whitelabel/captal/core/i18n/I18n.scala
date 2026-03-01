package whitelabel.captal.core.i18n

import io.taig.babel.{Decoder, Encoder}
import io.taig.babel.generic.auto.*

final case class I18n(
    welcome: I18n.Welcome,
    loading: I18n.Loading,
    error: I18n.Error,
    question: I18n.Question
)

object I18n:
  final case class Welcome(
      title: String,
      subtitle: String,
      steps: Welcome.Steps,
      button: Welcome.Button,
      selectLanguage: String
  )

  object Welcome:
    final case class Steps(
        step1: String,
        step2: String,
        step3: String
    )

    final case class Button(
        start: String,
        connecting: String
    )

  final case class Loading(
      message: String
  )

  final case class Error(
      title: String,
      retry: String,
      generic: String
  )

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
      invalidAnswer: String
  )

  given decoder: Decoder[I18n] = deriveDecoder[I18n]
  given encoder: Encoder[I18n] = deriveEncoder[I18n]
