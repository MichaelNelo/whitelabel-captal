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
      selectLanguage: String,
      authorized: Welcome.Authorized)

  object Welcome:
    final case class Steps(step1: String, step2: String, step3: String)
    object Steps:
      given Decoder[Steps] = deriveDecoder[Steps]
      given Encoder[Steps] = deriveEncoder[Steps]

    final case class Button(start: String, connecting: String)
    object Button:
      given Decoder[Button] = deriveDecoder[Button]
      given Encoder[Button] = deriveEncoder[Button]

    final case class Authorized(title: String, remaining: String)
    object Authorized:
      given Decoder[Authorized] = deriveDecoder[Authorized]
      given Encoder[Authorized] = deriveEncoder[Authorized]

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

  /** Summon the schema through this binding: `I18nSchema.derived` is `inline`, so summoning it
    * directly re-expands the whole fold at every call site.
    */
  given schema: I18nSchema[I18n] = I18nSchema.derived

  /** What each key is for, as an [[I18n]] whose leaves hold descriptions instead of translations.
    * Total by construction: adding a field above is a compile error until it is described here.
    */
  val descriptions: I18n = I18n(
    welcome = Welcome(
      title = "Large heading on the landing screen.",
      subtitle = "Subline under the title.",
      steps = Welcome.Steps(
        step1 = "Bullet 1 of the three-step explainer.",
        step2 = "Bullet 2 of the three-step explainer.",
        step3 = "Bullet 3 of the three-step explainer."),
      button = Welcome.Button(
        start = "Call to action that begins the flow, idle state.",
        connecting = "Call to action while the session is being created."),
      selectLanguage = "Label of the locale picker.",
      authorized = Welcome.Authorized(
        title = "Heading shown while the guest already has access.",
        remaining = "Countdown of the access left. Use {seconds}.")
    ),
    ready = Ready(
      title = "Thank-you heading once the flow is complete.",
      subtitle = "Subline of the ready screen, e.g. that browsing may begin.",
      resetButton = "Label of the reset button. Only rendered when ENVIRONMENT=dev."
    ),
    loading = Loading(message = "Generic spinner caption."),
    error = Error(
      title = "Error screen heading.",
      retry = "Label of the retry button.",
      generic = "Fallback error message body."),
    question = Question(
      submit = "Submit button on the last question.",
      next = "Next button on intermediate questions.",
      required = "Validation: the field is required.",
      invalidEmail = "Validation: email format.",
      invalidUrl = "Validation: URL format.",
      invalidPattern = "Validation: the regex pattern did not match.",
      minLength = "Validation: text too short. Use {min}.",
      maxLength = "Validation: text too long. Use {max}.",
      minSelections = "Validation: not enough options selected. Use {min}.",
      maxSelections = "Validation: too many options selected. Use {max}.",
      invalidOption = "Validation: the option is not in the allowed set.",
      ratingOutOfRange = "Validation: rating outside [min,max]. Use {min} and {max}.",
      numericOutOfRange = "Validation: number outside [min,max]. Use {min} and {max}.",
      dateOutOfRange = "Validation: date outside [min,max]. Use {min} and {max}.",
      invalidAnswer = "Validation: catch-all for anything the specific rules did not cover."
    ),
    video = Video(
      pageTitle = "Video screen heading.",
      continueIn = "Countdown before the flow continues. Use {seconds}.",
      watchComplete = "Banner shown once the video has ended.",
      markWatched = "Button confirming the video was watched, manual fallback.",
      loading = "Caption while the video buffers.",
      noVideoAvailable = "Message when no video matches the rotation.",
      payAttention = "Warning rendered above the player intro."
    )
  )
end I18n
