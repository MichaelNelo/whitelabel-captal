package whitelabel.captal.platform.repositories

import whitelabel.captal.core.infrastructure.{TermsRepository, TermsTextRepository, TermsVersion}
import whitelabel.captal.core.terms.TermsTranslation
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

  /** A version's texts, as `terms_translations` holds them. */
  def texts(quill: QuillSqlite): TermsTextRepository[Task] =
    new TermsTextRepository[Task]:
      def translations(version: TermsVersion): Task[List[TermsTranslation]] =
        Terms
          .translations(quill, version.id)
          .map(_.map(row => TermsTranslation(row.locale, row.body)))
          .orDie
