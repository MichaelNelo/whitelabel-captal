package whitelabel.captal.mcp.aws

import scala.jdk.CollectionConverters.*

import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client
import software.amazon.awssdk.services.elasticloadbalancingv2.model.{
  Action,
  ActionTypeEnum,
  CreateRuleRequest,
  DeleteRuleRequest,
  DescribeRulesRequest,
  ModifyRuleRequest,
  PathPatternConditionConfig,
  QueryStringConditionConfig,
  QueryStringKeyValuePair,
  RedirectActionConfig,
  RedirectActionStatusCodeEnum,
  Rule,
  RuleCondition
}
import whitelabel.captal.core.application.queries.{DispatchQuery, DispatchRoute}
import whitelabel.captal.core.device.Mac
import whitelabel.captal.core.location.{Destination, RedirectTarget}
import whitelabel.captal.mcp.tools.Queries
import zio.*

/** The portal dispatcher: a rule on the load balancer's HTTP listener per access point's MAC, which
  * redirects the guests UniFi sends to `/guest/s/…?ap=<mac>` where `core` says they go
  * ([[whitelabel.captal.core.location.Dispatch.destination]]). Written whenever what decides it
  * changes — an operation ends, an access point is registered, changed or removed — and every MAC
  * on startup, which also removes the rules of MACs no location holds any more.
  *
  * Never fails its caller: what it writes follows a change already committed, so a write that fails
  * is logged, and the next write of the same MAC, or the next startup, puts it right.
  */
trait DispatchRules:
  def write(which: DispatchQuery): UIO[Unit]

object DispatchRules:
  /** Writes nothing: a backoffice with no load balancer to write to, and the suites. */
  val off: DispatchRules = _ => ZIO.unit

  /** The path UniFi sends a guest to on its external portal server, for every site. */
  val GuestPath = "/guest/s/*"

  /** The query parameter UniFi names the access point's MAC in. */
  val ApKey = "ap"

  /** The rules on `listenerArn`, redirecting to the portal at `portalBase` (`PUBLIC_BASE_URL`). */
  def live(
      elb: ElasticLoadBalancingV2Client,
      listenerArn: String,
      portalBase: RedirectTarget,
      query: Queries.Dispatch): UIO[DispatchRules] = Semaphore
    .make(1)
    .map: one =>
      new DispatchRules:
        def write(which: DispatchQuery): UIO[Unit] = one.withPermit(
          query
            .execute(which)
            .flatMap:
              case Right(routes) =>
                ZIO.attemptBlocking(converge(routes, removeUnasked = which == DispatchQuery.All))
              case Left(error) =>
                ZIO.fail(new IllegalStateException(error.toString))
            .catchAllCause(cause =>
              ZIO.logErrorCause(s"The dispatch rules for $which were not written", cause)))

        /** Every MAC asked for to its rule: created, changed or removed; and, asked for every MAC,
          * the rules of those no location holds removed too.
          */
        private def converge(routes: List[DispatchRoute], removeUnasked: Boolean): Unit =
          val existing =
            elb
              .describeRules(DescribeRulesRequest.builder().listenerArn(listenerArn).build())
              .rules()
              .asScala
              .toList
          val mine = existing.flatMap(rule => macOf(rule).map(_ -> rule)).groupMap(_._1)(_._2)
          val used = scala
            .collection
            .mutable
            .Set
            .from(existing.flatMap(rule => rule.priority().toIntOption))
          routes.foreach: route =>
            val rules = mine.getOrElse(route.mac, Nil)
            route.destination match
              case Some(destination) =>
                val conditions = conditionsFor(route.mac)
                val action = redirectTo(destination)
                rules match
                  case first :: extra =>
                    elb.modifyRule(
                      ModifyRuleRequest
                        .builder()
                        .ruleArn(first.ruleArn())
                        .conditions(conditions*)
                        .actions(action)
                        .build())
                    extra.foreach(delete)
                  case Nil =>
                    val priority = DeployTargets.nextPriority(used.toSet)
                    used += priority
                    elb.createRule(
                      CreateRuleRequest
                        .builder()
                        .listenerArn(listenerArn)
                        .priority(priority)
                        .conditions(conditions*)
                        .actions(action)
                        .build())
                end match
              case None =>
                rules.foreach(delete)
            end match
          if removeUnasked then
            val asked = routes.map(_.mac).toSet
            mine.view.filterKeys(mac => !asked.contains(mac)).values.flatten.foreach(delete)
        end converge

        private def delete(rule: Rule): Unit =
          val _ = elb.deleteRule(DeleteRuleRequest.builder().ruleArn(rule.ruleArn()).build())

        private def redirectTo(destination: Destination): Action =
          val target =
            destination match
              case Destination.Portal(slug) =>
                portalBase.copy(path = s"${portalBase.path.stripSuffix("/")}/${slug.asString}/")
              case Destination.Elsewhere(target) =>
                target
          Action
            .builder()
            .`type`(ActionTypeEnum.REDIRECT)
            .redirectConfig(
              RedirectActionConfig
                .builder()
                .protocol(target.protocol.toUpperCase)
                .host(target.host)
                .port(target.port.toString)
                .path(target.path)
                .query(
                  target
                    .query
                    .fold(RedirectTarget.QueryKeyword)(own =>
                      s"$own&${RedirectTarget.QueryKeyword}"))
                .statusCode(RedirectActionStatusCodeEnum.HTTP_302)
                .build())
            .build()
        end redirectTo

  /** The two conditions a MAC's rule matches on: UniFi's path, and the MAC in `ap` as UniFi writes
    * it, its colons unencoded — the same in the raw query and the decoded one, whichever AWS
    * compares, which it does not document. Not with its colons encoded as well: AWS refuses a `%`
    * in a condition's value. The comparison is not case-sensitive.
    */
  def conditionsFor(mac: Mac): List[RuleCondition] = List(
    RuleCondition
      .builder()
      .field("path-pattern")
      .pathPatternConfig(PathPatternConditionConfig.builder().values(GuestPath).build())
      .build(),
    RuleCondition
      .builder()
      .field("query-string")
      .queryStringConfig(
        QueryStringConditionConfig
          .builder()
          .values(QueryStringKeyValuePair.builder().key(ApKey).value(mac.show).build())
          .build())
      .build()
  )

  /** The MAC a dispatch rule matches on: one on [[GuestPath]] with an `ap` value that is a MAC.
    * Every other rule on the listener is not this one's.
    */
  def macOf(rule: Rule): Option[Mac] =
    val conditions = rule.conditions().asScala.toList
    val onGuestPath = conditions.exists(condition =>
      Option(condition.pathPatternConfig()).exists(_.values().asScala.contains(GuestPath)))
    Option
      .when(onGuestPath)(conditions)
      .toList
      .flatten
      .flatMap(condition => Option(condition.queryStringConfig()).toList)
      .flatMap(_.values().asScala)
      .filter(_.key() == ApKey)
      .flatMap(pair => Mac.parse(pair.value()))
      .headOption
  end macOf

  /** The rules a backoffice with a load balancer writes: on `ALB_DISPATCH_LISTENER_ARN`, to the
    * portal at `PUBLIC_BASE_URL`. Off without either, or without a load balancer at all, with a
    * warning when a load balancer is there but the dispatch is not configured.
    */
  val layer: ZLayer[Option[InfraStore] & Queries.Dispatch, Nothing, DispatchRules] = ZLayer:
    for
      infra <- ZIO.service[Option[InfraStore]]
      query <- ZIO.service[Queries.Dispatch]
      rules <-
        infra match
          case None =>
            ZIO.succeed(off)
          case Some(store) =>
            val base = store.targets.publicBaseUrl.flatMap(url => RedirectTarget.of(url).toOption)
            (store.targets.dispatchListenerArn, base) match
              case (Some(listener), Some(portal)) =>
                live(store.elb, listener, portal, query)
              case _ =>
                ZIO.logWarning(
                  "No ALB_DISPATCH_LISTENER_ARN, or no PUBLIC_BASE_URL a redirect can carry: the access points' dispatch rules are not written") *>
                  ZIO.succeed(off)
    yield rules
end DispatchRules
