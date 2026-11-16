package whitelabel.captal.platform.queries

import io.getquill.*
import whitelabel.captal.core.application.Error
import whitelabel.captal.core.application.queries.{
  Query,
  TermsInForceQuery,
  TermsReport,
  TermsVersionReport
}
import whitelabel.captal.core.terms.TermsTranslation
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.{Terms, TermsRow}
import zio.*

/** [[TermsInForceQuery]], read from `terms` and `terms_translations`. */
object TermsInForce:
  def apply(quill: QuillSqlite): Query.Aux[Task, TermsInForceQuery, TermsReport] =
    new Query[Task, TermsInForceQuery]:
      type Result = TermsReport

      def execute(q: TermsInForceQuery): Task[Either[Error, TermsReport]] =
        import quill.*
        for
          current  <- Terms.current(quill)
          versions <- run(query[TermsRow].sortBy(_.version)(using Ord.desc).map(_.version))
          texts    <- ZIO.foreach(current)(terms => Terms.translations(quill, terms.id))
        yield Right(
          TermsReport(
            current = current.map(terms =>
              TermsVersionReport(
                terms.version,
                terms.createdAt,
                texts.toList.flatten.map(t => TermsTranslation(t.locale, t.body)))),
            earlier = versions.drop(1)
          ))
end TermsInForce
