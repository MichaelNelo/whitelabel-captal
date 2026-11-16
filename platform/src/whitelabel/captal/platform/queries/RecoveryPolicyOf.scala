package whitelabel.captal.platform.queries

import cats.data.NonEmptyChain
import whitelabel.captal.core.application.Error
import whitelabel.captal.core.application.queries.{
  Query,
  RecoveryPolicyOfQuery,
  RecoveryPolicyReport
}
import whitelabel.captal.core.location.Error as LocationError
import whitelabel.captal.core.recovery.PolicySetting
import whitelabel.captal.platform.LocationRecovery
import whitelabel.captal.platform.repositories.LocationRepositoryQuill
import whitelabel.captal.platform.schema.QuillSqlite
import zio.*

/** [[RecoveryPolicyOfQuery]], read from `location_recovery`: what the location set, the rest filled
  * in with the defaults by `core`'s `PolicyOverrides`.
  */
object RecoveryPolicyOf:
  def apply(quill: QuillSqlite): Query.Aux[Task, RecoveryPolicyOfQuery, RecoveryPolicyReport] =
    new Query[Task, RecoveryPolicyOfQuery]:
      type Result = RecoveryPolicyReport

      def execute(q: RecoveryPolicyOfQuery): Task[Either[Error, RecoveryPolicyReport]] =
        LocationRepositoryQuill(quill)
          .find(q.slug)
          .flatMap:
            case None =>
              ZIO.left(Error.Location(NonEmptyChain.one(LocationError.NotFound(q.slug))))
            case Some(location) =>
              LocationRecovery
                .find(quill, location.id.asString)
                .map: row =>
                  val overrides = LocationRecovery.overridesOf(row)
                  Right(
                    RecoveryPolicyReport(
                      slug = location.slug,
                      policy = overrides.policy,
                      grace = overrides.grace,
                      defaults = PolicySetting.values.toList.filterNot(overrides.set.contains)))
end RecoveryPolicyOf
