package whitelabel.captal.infra

import io.getquill.*
import whitelabel.captal.infra.schema.QuillSqlite
import whitelabel.captal.infra.schema.given
import zio.*

trait LocaleService:
  def listAvailable(): Task[List[String]]

object LocaleService:
  def listAvailable(): ZIO[LocaleService, Throwable, List[String]] =
    ZIO.serviceWithZIO[LocaleService](_.listAvailable())

  val layer: ZLayer[QuillSqlite, Nothing, LocaleService] =
    ZLayer.fromFunction(LocaleServiceQuill.apply)

object LocaleServiceQuill:
  def apply(quill: QuillSqlite): LocaleService =
    new LocaleService:
      import quill.*

      def listAvailable(): Task[List[String]] =
        run(
          query[LocalizedTextRow]
            .map(_.locale)
            .distinct
            .sortBy(l => l)
        ).orDie
