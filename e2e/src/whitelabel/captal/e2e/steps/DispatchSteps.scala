package whitelabel.captal.e2e.steps

import com.jamesward.ziohttp.mcp.{CallToolResult, ToolContent}
import io.cucumber.scala.{EN, ScalaDsl}
import whitelabel.captal.e2e.backoffice.{Backoffice, Locations}
import whitelabel.captal.e2e.scenario.World
import whitelabel.captal.e2e.venue.Venue
import whitelabel.captal.mcp.tools.{DeployTools, UnifiTools}

/** Where the load balancer sends a phone UniFi sends to the external portal server, as the access
  * point's dispatch rule says: an access point with an address of its own sends its guests there,
  * one no location has is turned away, and one two locations are configured with sends its guests
  * to the one that took it, deployed. That every other access point sends its guests to its own
  * location's portal is what every feature's arrival checks ([[Locations.Deployed.portal]]).
  */
final class DispatchSteps extends ScalaDsl with EN:
  /** What the load balancer answered the scenario's last phone: its status and `Location`. */
  private var answered = Option.empty[(Int, Option[String])]

  /** The access point the scenario's last phone joined through. */
  private var through = Option.empty[String]

  /** What the backoffice answered the scenario's last deploy tried. */
  private var tried = Option.empty[CallToolResult]

  Given("""a deployed location {string} whose access point sends its guests to {string}""") {
    (name: String, address: String) =>
      val location =
        Locations.once(s"dispatch-$name"):
          val created = Locations.create(name)
          Backoffice.tool[UnifiTools.UpdateAccessPointRequest, UnifiTools.StatusResponse](
            "update_access_point",
            UnifiTools.UpdateAccessPointRequest(
              created.slug,
              Venue.apFor(created.slug),
              redirectUrl = Some(address)))
          Locations.addAdvertiserVideo(created)
          Locations.deploy(created)
      World.use(name, location)
  }

  /** `taker` deployed, which takes its own access point; `waiter`, not deployed, configured with it
    * too, chosen on the page from the access points the system has.
    */
  Given(
    """a deployed location {string} and a location {string} configured with its access point""") {
    (taker: String, waiter: String) =>
      val took = Locations.once(s"dispatch-$taker")(Locations.deploy(Locations.create(taker)))
      val waiting =
        Locations.once(s"dispatch-$waiter"):
          val created = Locations.create(waiter)
          Locations.configureUnifi(
            created.slug,
            Venue.AccessMinutes,
            ap = Some(Venue.apFor(took.slug)))
          created
      World.use(taker, took)
      World.use(waiter, waiting)
  }

  When("""the operator tries to deploy {string}""") { (name: String) =>
    tried = Some(
      Backoffice.call(
        "deploy_location",
        Backoffice.args(DeployTools.DeployRequest(World.location(name).slug))))
  }

  Then("""the deploy is refused, since {string} took the access point""") { (taker: String) =>
    val result = tried.getOrElse(throw AssertionError("No deploy was tried yet"))
    val said =
      result
        .content
        .collect { case ToolContent.Text(text, _) =>
          text
        }
        .mkString
    val slug = World.location(taker).slug
    assert(
      result.isError.contains(true) && said.contains(Venue.apFor(slug)) && said.contains(slug),
      s"The deploy was not refused for the access point $slug took: $said"
    )
  }

  When("""the operator deploys {string}""") { (name: String) =>
    Locations.deploy(World.location(name))
  }

  When("""a phone joins through the access point of {string}""") { (name: String) =>
    val ap = Venue.apFor(World.location(name).slug)
    through = Some(ap)
    val query = s"id=02:aa:bb:cc:dd:ee&ap=$ap&ssid=E2E"
    // The rule is written once the deploy has ended: asked again until it answers.
    val deadline = System.nanoTime + 60_000_000_000L
    def ask(): (Int, Option[String]) =
      Venue.dispatch(query) match
        case (302, location) =>
          (302, location)
        case _ if System.nanoTime < deadline =>
          Thread.sleep(500)
          ask()
        case other =>
          other
    answered = Some(ask())
  }

  When("""a phone joins through the access point {string}, which no location has""") {
    (mac: String) =>
      answered = Some(Venue.dispatch(s"id=02:aa:bb:cc:dd:ee&ap=$mac&ssid=E2E"))
  }

  /** The address's host, path and own query, which is what LocalStack carries of it: it answers the
    * rule's `#{query}` as it is, where AWS puts the phone's query (see the dispatch plan).
    */
  Then("""the load balancer sends it to {string}""") { (address: String) =>
    val (status, location) = answered.getOrElse(throw AssertionError("No phone has joined yet"))
    val expected = java.net.URI.create(address)
    val sent = location.map(java.net.URI.create)
    assert(
      status == 302 &&
        sent.exists(to =>
          to.getHost == expected.getHost && to.getPath == expected.getPath &&
            Option(to.getQuery).exists(_.startsWith(expected.getQuery))),
      s"The load balancer answered $status${location.fold("")(l =>
          s" to $l")}, not a redirect to $address"
    )
  }

  /** Asked again until it does: the rules are written once the deploy has ended, and until then the
    * access point's rule still sends its guests where it did.
    */
  Then("""the load balancer sends it to the portal of {string}""") { (name: String) =>
    val ap = through.getOrElse(throw AssertionError("No phone has joined yet"))
    val location = World.location(name)
    val expected = java.net.URI.create(location.address)
    def portal(answer: (Int, Option[String])) =
      answer match
        case (302, Some(to)) =>
          val sent = java.net.URI.create(to)
          sent.getHost == expected.getHost && sent.getPath == expected.getPath
        case _ =>
          false
    val deadline = System.nanoTime + 60_000_000_000L
    def ask(): (Int, Option[String]) =
      val answer = Venue.dispatch(s"id=02:aa:bb:cc:dd:ee&ap=$ap&ssid=E2E")
      if portal(answer) || System.nanoTime > deadline then
        answer
      else
        Thread.sleep(500)
        ask()
    val (status, sent) = ask()
    assert(
      portal((status, sent)),
      s"The load balancer answered $status${sent.fold("")(l => s" to $l")}, not ${location
          .address}")
  }

  Then("""the load balancer answers {int}""") { (status: Int) =>
    val (got, location) = answered.getOrElse(throw AssertionError("No phone has joined yet"))
    assert(got == status, s"The load balancer answered $got${location.fold("")(l => s" to $l")}")
  }
end DispatchSteps
