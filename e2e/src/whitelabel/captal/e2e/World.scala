package whitelabel.captal.e2e

import java.nio.file.{Files, Path}

import scala.collection.mutable

import io.cucumber.scala.{EN, PendingException, ScalaDsl, Scenario}

/** What the scenario running now has made, by the names its steps give them: the locations it uses
  * and its guests. Every step class reads and writes it, which is how a step of one class acts on
  * what a step of another set up. Scenarios run one at a time, so there is one world, emptied
  * before each of them.
  */
object World:
  private val locations = mutable.LinkedHashMap.empty[String, Locations.Deployed]
  private val guests = mutable.Map.empty[String, Portal.Guest]

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
    val guest = Portal.arrive(name, at, locale)
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

  private[e2e] def begin(scenario: Scenario): Unit =
    reset()
    val feature = Path.of(scenario.getUri.getPath).getFileName.toString.stripSuffix(".feature")
    val name = scenario.getName.replaceAll("[^A-Za-z0-9]+", "-").stripPrefix("-").stripSuffix("-")
    scenarioRecording = recordings.map(_.resolve(feature).resolve(s"${scenario.getLine}-$name"))

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
    locations.clear()
end World

/** Empties the [[World]] around each scenario. */
final class WorldHooks extends ScalaDsl with EN:
  Before { (scenario: Scenario) =>
    World.begin(scenario)
  }

  After { (scenario: Scenario) =>
    World.finish(scenario)
  }
end WorldHooks
