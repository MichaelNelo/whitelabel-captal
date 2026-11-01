package whitelabel.captal.e2e

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

  private[e2e] def reset(): Unit =
    guests.values.foreach(_.close())
    guests.clear()
    locations.clear()
end World

/** Empties the [[World]] around each scenario. */
final class WorldHooks extends ScalaDsl with EN:
  Before { (_: Scenario) =>
    World.reset()
  }

  After { (_: Scenario) =>
    World.reset()
  }
end WorldHooks
