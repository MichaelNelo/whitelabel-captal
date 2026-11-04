package whitelabel.captal.platform.repositories

import whitelabel.captal.core.infrastructure.{TermsRepository, TermsVersion}
import whitelabel.captal.platform.Terms
import whitelabel.captal.platform.schema.QuillSqlite
import zio.*

object TermsRepositoryQuill:
  def apply(quill: QuillSqlite): TermsRepository[Task] =
    new TermsRepository[Task]:
      def current: Task[Option[TermsVersion]] =
        Terms.current(quill).map(_.map(row => TermsVersion(row.id, row.version))).orDie

  val layer: ZLayer[QuillSqlite, Nothing, TermsRepository[Task]] = ZLayer.fromFunction(
    (quill: QuillSqlite) => apply(quill))
