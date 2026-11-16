package whitelabel.captal.platform.queries

import cats.data.NonEmptyChain
import whitelabel.captal.core.application.Error
import whitelabel.captal.core.application.queries.{
  IconOfQuery,
  PublishedDocument,
  Query,
  StylesOfQuery
}
import whitelabel.captal.core.location.{Error as LocationError, Slug}
import whitelabel.captal.platform.aws.{Asset, AssetStore}
import whitelabel.captal.platform.repositories.LocationRepositoryQuill
import whitelabel.captal.platform.schema.QuillSqlite
import zio.*

/** [[StylesOfQuery]] and [[IconOfQuery]], read from where each location's portal reads them. */
object PublishedDocuments:
  def styles(
      quill: QuillSqlite,
      store: AssetStore): Query.Aux[Task, StylesOfQuery, PublishedDocument] =
    new Query[Task, StylesOfQuery]:
      type Result = PublishedDocument
      def execute(q: StylesOfQuery) = read(quill, store, q.slug, Asset.Styles)

  def icon(quill: QuillSqlite, store: AssetStore): Query.Aux[Task, IconOfQuery, PublishedDocument] =
    new Query[Task, IconOfQuery]:
      type Result = PublishedDocument
      def execute(q: IconOfQuery) = read(quill, store, q.slug, Asset.BrandIcon)

  private def read(
      quill: QuillSqlite,
      store: AssetStore,
      slug: Slug,
      asset: Asset): Task[Either[Error, PublishedDocument]] = LocationRepositoryQuill(quill)
    .find(slug)
    .flatMap:
      case None =>
        ZIO.left(Error.Location(NonEmptyChain.one(LocationError.NotFound(slug))))
      case Some(location) =>
        store
          .get(location.slug.asString, asset)
          .mapError(reason => IllegalStateException(s"Reading ${asset.fileName}: $reason"))
          .map(content => Right(PublishedDocument(location.slug, content)))
end PublishedDocuments
