package whitelabel.captal.client.i18n

object Messages:
  private val translations: Map[String, Map[String, String]] = Map(
    "es" -> MessagesEs.messages,
    "en" -> MessagesEn.messages
  )

  val defaultLocale: String = "es"

  def get(key: String, locale: String): String =
    translations
      .get(locale)
      .flatMap(_.get(key))
      .orElse(translations.get(defaultLocale).flatMap(_.get(key)))
      .getOrElse(s"[$key]")
