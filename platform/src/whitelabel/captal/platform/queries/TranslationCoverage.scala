package whitelabel.captal.platform.queries

import cats.data.NonEmptyChain
import io.getquill.*
import whitelabel.captal.core.application.Error
import whitelabel.captal.core.application.queries.{
  Query,
  TranslationCoverageQuery,
  TranslationCoverageReport
}
import whitelabel.captal.core.i18n.Coverage
import whitelabel.captal.core.location.Error as LocationError
import whitelabel.captal.platform.LocalizedTextRow
import whitelabel.captal.platform.repositories.{LocationRepositoryQuill, TranslationRepositoryQuill}
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import zio.*

/** [[TranslationCoverageQuery]], read from `localized_texts` and measured by `core`'s [[Coverage]].
  */
object TranslationCoverage:
  def apply(
      quill: QuillSqlite): Query.Aux[Task, TranslationCoverageQuery, TranslationCoverageReport] =
    new Query[Task, TranslationCoverageQuery]:
      type Result = TranslationCoverageReport

      def execute(q: TranslationCoverageQuery): Task[Either[Error, TranslationCoverageReport]] =
        import quill.*
        LocationRepositoryQuill(quill)
          .find(q.slug)
          .flatMap:
            case None =>
              ZIO.left(Error.Location(NonEmptyChain.one(LocationError.NotFound(q.slug))))
            case Some(location) =>
              run(
                query[LocalizedTextRow].filter(r =>
                  r.locationId == lift(Option(location.id.asString)) &&
                    r.category == lift(TranslationRepositoryQuill.Category) && r.isActive == 1))
                .map: rows =>
                  val texts =
                    rows
                      .groupBy(_.locale)
                      .view
                      .mapValues(_.map(row => row.entityId -> row.value).toMap)
                      .toMap
                  Right(
                    TranslationCoverageReport(
                      location.slug,
                      Coverage.required.size,
                      Coverage.of(texts)))
      end execute
end TranslationCoverage
