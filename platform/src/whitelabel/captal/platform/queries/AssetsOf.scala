package whitelabel.captal.platform.queries

import cats.data.NonEmptyChain
import whitelabel.captal.core.application.Error
import whitelabel.captal.core.application.queries.{AssetCount, AssetsOfQuery, Query}
import whitelabel.captal.core.location.Slug
import whitelabel.captal.platform.aws.AssetStore
import zio.*

/** [[AssetsOfQuery]], counted under the location's prefix in the assets bucket. */
object AssetsOf:
  def apply(assets: AssetStore): Query.Aux[Task, AssetsOfQuery, AssetCount] =
    new Query[Task, AssetsOfQuery]:
      type Result = AssetCount

      def execute(q: AssetsOfQuery): Task[Either[Error, AssetCount]] =
        Slug.parse(q.slug) match
          case Left(invalid) =>
            ZIO.succeed(Left(Error.Location(NonEmptyChain.one(invalid))))
          case Right(slug) =>
            val prefix = s"${slug.asString}/"
            assets
              .listPrefix(prefix)
              .mapError(new RuntimeException(_))
              .map(keys =>
                Right(AssetCount(keys.size, keys.count(_.startsWith(s"${prefix}videos/")))))
end AssetsOf
