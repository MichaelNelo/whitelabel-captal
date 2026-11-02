package whitelabel.captal.e2e.scenario

import java.nio.file.{Files, Path}

import scala.collection.mutable

import com.microsoft.playwright.{BrowserContext, Page}
import io.cucumber.scala.{EN, PendingException, ScalaDsl, Scenario}
import whitelabel.captal.e2e.backoffice.Locations
import whitelabel.captal.e2e.guest.Portal

/** What the scenario running now has made, by the names its steps give them: the locations it uses,
  * its guests, and the operator's browser. Every step class reads and writes it, which is how a
  * step of one class acts on what a step of another set up. Scenarios run one at a time, so there
  * is one world, emptied before each of them.
  */
object World:
  private val locations = mutable.LinkedHashMap.empty[String, Locations.Deployed]
  private val guests = mutable.Map.empty[String, Portal.Guest]
  private var desk = Option.empty[BrowserContext]
  private val operatorPages = mutable.ListBuffer.empty[(Page, String)]

  /** A new tab of the operator's browser, one per scenario, for the page `tool` opened. Recorded as
    * `operator-<n>-<tool>.webm`.
    */
  private[e2e] def operatorPage(tool: String): Page =
    val context = desk.getOrElse:
      val opened = Portal.desk()
      desk = Some(opened)
      opened
    val page = context.newPage()
    operatorPages += page -> s"operator-${operatorPages.size + 1}-${tool.replace('_', '-')}"
    page

  /** `location` as `name` for the rest of the scenario, and the one its guests go to unless a step
    * names another.
    */
  def use(name: String, location: Locations.Deployed): Unit =
    locations.remove(name)
    locations(name) = location

  /** The location the scenario named last. */
  def location: Locations.Deployed = locations
    .lastOption
    .map(_._2)
    .getOrElse(throw IllegalStateException("No location in this scenario yet"))

  def location(name: String): Locations.Deployed = locations.getOrElse(
    name,
    throw IllegalStateException(s"No location called $name in this scenario"))

  def guest(name: String): Portal.Guest = guests.getOrElse(
    name,
    throw IllegalStateException(s"No guest called $name in this scenario"))

  /** `name` on a phone of their own, set to `locale`, sent by the access point to `at`'s portal. */
  def arrive(
      name: String,
      at: Locations.Deployed = location,
      locale: String = "en-US"): Portal.Guest =
    val guest = Portal.arrive(name, at, locale, streamThroughLoadBalancer)
    guests(name) = guest
    guest

  /** `guest` as its name says from now on: the same guest, changed, as a step left them. */
  def keep(guest: Portal.Guest): Portal.Guest =
    guests(guest.name) = guest
    guest

  /** A step whose subject does not exist yet: reported pending, with why, rather than faked. */
  def pending(reason: String): Nothing =
    println(s"[pending] $reason")
    throw PendingException()

  /** Where the running scenario's screens are recorded, when the run records them
    * (`E2E_RECORDINGS`): `<feature>/<line>-<scenario>`, one video per page a guest had open.
    */
  private[e2e] def recording: Option[Path] = scenarioRecording

  private val recordings = sys.env.get("E2E_RECORDINGS").filter(_.nonEmpty).map(Path.of(_))
  private var scenarioRecording = Option.empty[Path]

  /** Whether the scenario's guests follow the server's event streams through the load balancer
    * rather than the distribution: a scenario tagged [[StreamThroughLoadBalancer]], which needs a
    * stream to reach a page while it is open (see [[Portal.arrive]]).
    */
  private var streamThroughLoadBalancer = false

  val StreamThroughLoadBalancer = "@stream-through-load-balancer"

  /** How long a view stays on screen before the next one, for a recording a person can follow
    * (`E2E_LINGER_SECONDS`): after every step, and on the operator's pages
    * ([[whitelabel.captal.e2e.backoffice.Operator]]). None by default.
    */
  private[e2e] val linger: Option[Double] = sys
    .env
    .get("E2E_LINGER_SECONDS")
    .flatMap(_.toDoubleOption)
    .filter(_ > 0)
    .map(_ * 1000)

  /** Starts the scenario's recording afresh: what an earlier run recorded for it, failed or not, is
    * removed; other scenarios' recordings stay.
    */
  private[e2e] def begin(scenario: Scenario): Unit =
    reset()
    streamThroughLoadBalancer = scenario.getSourceTagNames.contains(StreamThroughLoadBalancer)
    val feature = Path.of(scenario.getUri.getPath).getFileName.toString.stripSuffix(".feature")
    val name = scenario.getName.replaceAll("[^A-Za-z0-9]+", "-").stripPrefix("-").stripSuffix("-")
    scenarioRecording = recordings.map(_.resolve(feature).resolve(s"${scenario.getLine}-$name"))
    for
      recorded <- scenarioRecording
      earlier  <- List(recorded, recorded.resolveSibling(s"${recorded.getFileName} (failed)"))
      if Files.isDirectory(earlier)
    do
      val files = Files.walk(earlier)
      try files.sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
      finally files.close()

  /** Closes the scenario's phones, which writes their videos, and marks a failed scenario's. */
  private[e2e] def finish(scenario: Scenario): Unit =
    reset()
    for
      recorded <- scenarioRecording
      if Files.isDirectory(recorded)
    do
      Files.deleteIfExists(recorded.resolve(".raw"))
      if scenario.isFailed then
        Files.move(recorded, recorded.resolveSibling(s"${recorded.getFileName} (failed)"))
    scenarioRecording = None

  private def reset(): Unit =
    guests.values.foreach(_.close())
    guests.clear()
    desk.foreach(Portal.close(_, operatorPages.toList))
    desk = None
    operatorPages.clear()
    locations.clear()
end World

/** Once every scenario has run, destroys the locations the features made
  * ([[whitelabel.captal.e2e.backoffice.Locations.sweep]]), unless the run was told to keep them
  * (`E2E_KEEP_LOCATIONS`). Not in a dry run, which has no backoffice to reach.
  */
object RunHooks extends ScalaDsl with EN:
  AfterAll {
    val keep = sys.env.get("E2E_KEEP_LOCATIONS").contains("true")
    if sys.env.contains("E2E_MCP_URL") && !keep then
      whitelabel.captal.e2e.backoffice.Locations.sweep()
  }
end RunHooks

/** Empties the [[World]] around each scenario. */
final class WorldHooks extends ScalaDsl with EN:
  Before { (scenario: Scenario) =>
    World.begin(scenario)
  }

  // What the step left on screen stays there a while before the next step changes it.
  AfterStep { (_: Scenario) =>
    World.linger.foreach(millis => Thread.sleep(millis.toLong))
  }

  After { (scenario: Scenario) =>
    World.finish(scenario)
  }
end WorldHooks
