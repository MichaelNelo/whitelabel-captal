package whitelabel.captal.core.infrastructure

import whitelabel.captal.core.location.LocationId
import whitelabel.captal.core.recovery.PolicyOverrides

trait RecoveryPolicyRepository[F[_]]:
  /** What the location set of its recovery settings: nothing, when it set nothing. */
  def of(location: LocationId): F[PolicyOverrides]
