package whitelabel.captal.api

object LocaleDetector:
  val defaultLocale: String = "es"

  /** Parses Accept-Language header and returns the preferred locale code. Falls back to "en" if
    * header is missing or unparseable. The DB query handles fallback if the locale doesn't exist in
    * localized_texts.
    */
  def detect(acceptLanguage: Option[String]): String = acceptLanguage
    .flatMap(parseAcceptLanguage(_).headOption)
    .getOrElse(defaultLocale)

  private def parseAcceptLanguage(header: String): List[String] =
    // Parse "es-ES,es;q=0.9,en;q=0.8" -> List("es", "en") ordered by quality
    header
      .split(",")
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(parseLanguageTag)
      .sortBy(-_._2)
      .map(_._1)
      .filter(_.nonEmpty)
      .toList

  private def parseLanguageTag(tag: String): (String, Double) =
    tag.split(";") match
      case Array(lang, quality) =>
        val q =
          quality.trim match
            case s if s.startsWith("q=") =>
              s.drop(2).toDoubleOption.getOrElse(1.0)
            case _ =>
              1.0
        (extractLanguageCode(lang), q)
      case Array(lang) =>
        (extractLanguageCode(lang), 1.0)
      case _ =>
        ("", 0.0)

  private def extractLanguageCode(lang: String): String =
    // "es-ES" -> "es", "en-US" -> "en"
    lang.trim.split("-").headOption.getOrElse("").toLowerCase
end LocaleDetector
