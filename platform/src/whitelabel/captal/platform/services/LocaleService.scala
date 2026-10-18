package whitelabel.captal.platform.services

import io.getquill.*
import whitelabel.captal.core.i18n.{I18n, I18nSchema}
import whitelabel.captal.platform.LocalizedTextRow
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
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

  val layer: ZLayer[QuillSqlite, Nothing, LocaleService] = ZLayer.fromFunction(
    (quill: QuillSqlite) => LocaleServiceQuill(quill, None))
end LocaleService

object LocaleServiceQuill:
  def apply(quill: QuillSqlite, locationId: Option[String]): LocaleService =
    new LocaleService:
      import quill.*

      def listAvailable(): Task[List[String]] =
        run(query[LocalizedTextRow].filter(_.isActive == 1).map(_.locale).distinct.sortBy(l => l))
          .orDie

      def getMessages(locale: String, category: String): Task[Map[String, String]] =
        run(
          query[LocalizedTextRow].filter(r =>
            r.locale == lift(locale) && r.category == lift(category) && r.isActive == 1))
          .map(rows => rows.map(r => (r.entityId, r.value)).toMap)
          .orDie

      def getI18n(locale: String): Task[I18n] =
        val locId = locationId
        run(
          query[LocalizedTextRow].filter(r =>
            r.locale == lift(locale) && r.category == lift("frontend") &&
              r.locationId == lift(locId) && r.isActive == 1))
          .map(rows => rows.map(r => (r.entityId, r.value)).toMap)
          .map(messages => I18nSchema.decode[I18n](messages))
          .orDie
end LocaleServiceQuill
