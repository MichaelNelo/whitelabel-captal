package whitelabel.captal.core.survey.question

import java.time.{DateTimeException, LocalDate}

/** `YYYY-MM-DD` in and out, without `DateTimeFormatter`.
  *
  * `LocalDate.parse` and `DateTimeFormatter.ISO_LOCAL_DATE` reach a general parser that builds a
  * pattern from field descriptors and can read any format anyone might configure. On the JVM that
  * costs nothing — it is already in the JDK. In the browser every byte of it is compiled and
  * shipped, and it measured at roughly 26 KB of the bundle for a shape that is always ten
  * characters with dashes in fixed places.
  *
  * The validation is not lost: `LocalDate.of` rejects month 13 and February 30 exactly as the
  * parser would. What is given up is tolerance for formats we never send — the wire format is ours
  * on both ends, and `<input type="date">` is specified to produce this one.
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
      // `toInt` fails with NumberFormatException where the parser would have said
      // DateTimeParseException. Callers catch one or the other, so this keeps them honest.
      case e: NumberFormatException =>
        throw new DateTimeException(s"Not an ISO-8601 date: '$text'", e)

  /** The inverse. `LocalDate.toString` is already formatter-free, but going through this keeps both
    * directions in one place and named.
    */
  def format(date: LocalDate): String = date.toString
end IsoDate
