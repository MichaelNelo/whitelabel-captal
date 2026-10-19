package whitelabel.captal.core.survey.question

import java.time.{DateTimeException, LocalDate}

/** `YYYY-MM-DD` in and out, deliberately without `DateTimeFormatter` — do not route through
  * `LocalDate.parse`, whose general parser is dead weight in the Scala.js bundle. Accepts no other
  * format; anything else throws `DateTimeException`.
  */
object IsoDate:

  def parse(text: String): LocalDate =
    val firstDash = text.indexOf('-')
    val secondDash =
      if firstDash < 0 then
        -1
      else
        text.indexOf('-', firstDash + 1)
    if firstDash <= 0 || secondDash <= firstDash || secondDash == text.length - 1 then
      throw new DateTimeException(s"Not an ISO-8601 date: '$text'")
    try
      LocalDate.of(
        text.substring(0, firstDash).toInt,
        text.substring(firstDash + 1, secondDash).toInt,
        text.substring(secondDash + 1).toInt)
    catch
      // Re-wrapped so callers only ever have to catch DateTimeException.
      case e: NumberFormatException =>
        throw new DateTimeException(s"Not an ISO-8601 date: '$text'", e)

  /** The inverse of [[parse]]. */
  def format(date: LocalDate): String = date.toString
end IsoDate
