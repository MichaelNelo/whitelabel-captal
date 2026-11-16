package whitelabel.captal.platform.repositories

import whitelabel.captal.core.infrastructure.RecoveryPolicyRepository
import whitelabel.captal.core.location.LocationId
import whitelabel.captal.core.recovery.PolicyOverrides
import whitelabel.captal.platform.LocationRecovery
import whitelabel.captal.platform.schema.QuillSqlite
import zio.*

/** A location's recovery settings as `location_recovery` holds them: a row only for what it set. */
object RecoveryPolicyRepositoryQuill:
  def apply(quill: QuillSqlite): RecoveryPolicyRepository[Task] =
    new RecoveryPolicyRepository[Task]:
      def of(location: LocationId): Task[PolicyOverrides] = LocationRecovery
        .find(quill, location.asString)
        .map(LocationRecovery.overridesOf)
