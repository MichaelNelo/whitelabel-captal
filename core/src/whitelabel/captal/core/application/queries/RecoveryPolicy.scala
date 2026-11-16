package whitelabel.captal.core.application.queries

import java.time.Duration

import whitelabel.captal.core.location.Slug
import whitelabel.captal.core.recovery.{PolicySetting, WaitPolicy}

/** The recovery settings the location `slug` applies. */
final case class RecoveryPolicyOfQuery(slug: Slug)

/** What the location applies, whether it set it or takes the default: the policy its portal offers
  * a way out with, the grace its playbacks get, and the settings it takes from the defaults.
  */
final case class RecoveryPolicyReport(
    slug: Slug,
    policy: WaitPolicy,
    grace: Duration,
    defaults: List[PolicySetting])
