package whitelabel.captal.infra.services

import io.getquill.*
import whitelabel.captal.core.i18n.I18n
import whitelabel.captal.infra.LocalizedTextRow
import whitelabel.captal.infra.schema.QuillSqlite
import whitelabel.captal.infra.schema.given
import zio.*

trait LocaleService:
  def listAvailable(): Task[List[String]]
  def getMessages(locale: String, category: String): Task[Map[String, String]]
  def getI18n(locale: String): Task[I18n]

object LocaleService:
  def listAvailable()
      : ZIO[LocaleService, Throwable, List[String]] = ZIO.serviceWithZIO[LocaleService](
    _.listAvailable())

  def getMessages(locale: String, category: String)
      : ZIO[LocaleService, Throwable, Map[String, String]] = ZIO.serviceWithZIO[LocaleService](
    _.getMessages(locale, category))

  def getI18n(locale: String): ZIO[LocaleService, Throwable, I18n] = ZIO.serviceWithZIO[
    LocaleService](_.getI18n(locale))

  def buildI18n(messages: Map[String, String]): I18n =
    def get(key: String): String = messages.getOrElse(key, s"[$key]")

    I18n(
      welcome = I18n.Welcome(
        title = get("welcome.title"),
        subtitle = get("welcome.subtitle"),
        steps = I18n
          .Welcome
          .Steps(
            step1 = get("welcome.steps.step1"),
            step2 = get("welcome.steps.step2"),
            step3 = get("welcome.steps.step3")),
        button = I18n
          .Welcome
          .Button(
            start = get("welcome.button.start"),
            connecting = get("welcome.button.connecting")),
        selectLanguage = get("welcome.selectLanguage")
      ),
      ready = I18n.Ready(
        title = get("ready.title"),
        subtitle = get("ready.subtitle"),
        resetButton = get("ready.resetButton")),
      loading = I18n.Loading(message = get("loading.message")),
      error = I18n.Error(
        title = get("error.title"),
        retry = get("error.retry"),
        generic = get("error.generic")),
      question = I18n.Question(
        submit = get("question.submit"),
        next = get("question.next"),
        required = get("question.required"),
        invalidEmail = get("question.invalidEmail"),
        invalidUrl = get("question.invalidUrl"),
        invalidPattern = get("question.invalidPattern"),
        minLength = get("question.minLength"),
        maxLength = get("question.maxLength"),
        minSelections = get("question.minSelections"),
        maxSelections = get("question.maxSelections"),
        invalidOption = get("question.invalidOption"),
        ratingOutOfRange = get("question.ratingOutOfRange"),
        numericOutOfRange = get("question.numericOutOfRange"),
        dateOutOfRange = get("question.dateOutOfRange"),
        invalidAnswer = get("question.invalidAnswer")
      ),
      video = I18n.Video(
        pageTitle = get("video.pageTitle"),
        continueIn = get("video.continueIn"),
        watchComplete = get("video.watchComplete"),
        markWatched = get("video.markWatched"),
        loading = get("video.loading"),
        noVideoAvailable = get("video.noVideoAvailable"),
        payAttention = get("video.payAttention")
      )
    )
  end buildI18n

  val layer: ZLayer[QuillSqlite, Nothing, LocaleService] = ZLayer.fromFunction(
    LocaleServiceQuill.apply)
end LocaleService

object LocaleServiceQuill:
  def apply(quill: QuillSqlite): LocaleService =
    new LocaleService:
      import quill.*

      def listAvailable(): Task[List[String]] =
        run(query[LocalizedTextRow].map(_.locale).distinct.sortBy(l => l)).orDie

      def getMessages(locale: String, category: String): Task[Map[String, String]] =
        run(
          query[LocalizedTextRow].filter(r =>
            r.locale == lift(locale) && r.category == lift(category)))
          .map(rows => rows.map(r => (r.entityId, r.value)).toMap)
          .orDie

      def getI18n(locale: String): Task[I18n] = getMessages(locale, "frontend").map(
        LocaleService.buildI18n)
