package whitelabel.captal.e2e.steps

import com.microsoft.playwright.Page
import io.cucumber.scala.{EN, ScalaDsl}
import whitelabel.captal.e2e.scenario.World

/** The way out of an error, as the guest's page shows it: the error page and its countdown, the
  * shortening when other guests run into the same error, reloading the page, and the code staff
  * look an error up by. The error page's copy is its own, not a translation key: it cannot count on
  * the server for it, so it is in English and Spanish by the browser's language, as its script has
  * it.
  *
  * Nothing makes an error happen on demand, so the steps that would cause one are pending; the rest
  * check the page as an error leaves it.
  */
final class ErrorRecoverySteps extends ScalaDsl with EN:
  final private case class Copy(title: String, shared: String, unrecoverable: String)

  private val English = Copy(
    title = "Something went wrong",
    shared = "Other guests ran into the same problem, so the wait is shorter.",
    unrecoverable = "We couldn't give you access. Show this code to the staff here:"
  )

  private val Spanish = Copy(
    title = "Algo salió mal",
    shared = "A otros invitados les pasó lo mismo, así que la espera es más corta.",
    unrecoverable = "No pudimos darte acceso. Mostrale este código al personal del lugar:"
  )

  private val Countdown = """(\d+):(\d{2})""".r
  private val UuidText = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"

  private def copyOf(page: Page): Copy =
    if page.evaluate("navigator.language").toString.toLowerCase.startsWith("en") then
      English
    else
      Spanish

  private def message(page: Page): String = page.locator("#message").textContent()

  /** The seconds the countdown reads, when it reads any. */
  private def secondsShown(page: Page): Option[Int] = Countdown
    .findFirstMatchIn(message(page))
    .map(found => found.group(1).toInt * 60 + found.group(2).toInt)

  /** Waits until `condition` holds, polling every second, or fails with `what` after `seconds`. */
  private def within(seconds: Long, what: String)(condition: => Boolean): Unit =
    val deadline = System.nanoTime + seconds * 1_000_000_000L
    while !scala.util.Try(condition).getOrElse(false) do
      if System.nanoTime > deadline then
        throw AssertionError(s"$what, within $seconds seconds")
      Thread.sleep(1000)

  /** Waits until the page reads a value, polling every half second, and answers it. */
  private def await[A](seconds: Long, what: String)(read: => Option[A]): A =
    val deadline = System.nanoTime + seconds * 1_000_000_000L
    var found = scala.util.Try(read).toOption.flatten
    while found.isEmpty do
      if System.nanoTime > deadline then
        throw AssertionError(s"$what, within $seconds seconds")
      Thread.sleep(500)
      found = scala.util.Try(read).toOption.flatten
    found.get

  /** The fault injection the scenarios need: nothing in the stack makes an error happen on demand.
    */
  private def provoked(what: String): Nothing = World.pending(what)

  When("""the portal fails for {word} with a server error""") { (name: String) =>
    provoked(
      s"a fault injected into the API, so that the portal answers $name with a recorded error")
  }

  When("""the portal's code throws for {word}""") { (name: String) =>
    provoked(s"a fault injected into the bundle, so that the portal's code throws for $name")
  }

  When("""{word} hits the same error on another phone""") { (name: String) =>
    provoked(s"the same fault in the bundle, reached from a second phone by $name")
  }

  When("""{word} waits {int} seconds""") { (_: String, seconds: Int) =>
    Thread.sleep(seconds * 1000L)
  }

  When("""{word} reloads the page""") { (name: String) =>
    World.guest(name).page.reload()
  }

  /** The error page's fragment holds its offer: the error's id and when the wait ends, by the
    * device's clock. A reload that resumes keeps both; a new report would bring a new id.
    */
  When("""{word} reloads the error page""") { (name: String) =>
    val page = World.guest(name).page
    val before = page.evaluate("location.hash").toString
    page.reload()
    val after = page.evaluate("location.hash").toString
    if before != after then
      throw AssertionError(
        s"$name's error page was reported again on reload: $before became $after")
    within(30, s"$name's error page shows no countdown after the reload")(
      secondsShown(page).isDefined)
  }

  Then("""{word} sees the error page""") { (name: String) =>
    val page = World.guest(name).page
    within(30, s"$name does not see the error page")(
      page.locator("#title").textContent() == copyOf(page).title)
  }

  Then("""{word} is told to wait about {int} seconds""") { (name: String, expected: Int) =>
    val page = World.guest(name).page
    val shown = await(60, s"$name's error page shows no countdown")(secondsShown(page))
    if shown < expected - 10 || shown > expected then
      throw AssertionError(
        s"$name is told to wait $shown seconds, where about $expected was expected")
    if message(page).contains(copyOf(page).shared) then
      throw AssertionError(s"$name's first error is shown as shared: ${message(page)}")
  }

  Then("""{word}'s countdown reads at most {int} seconds""") { (name: String, max: Int) =>
    val page = World.guest(name).page
    val shown = await(60, s"$name's error page shows no countdown")(secondsShown(page))
    if shown > max then
      throw AssertionError(s"$name's countdown reads $shown seconds, more than $max")
  }

  Then("""{word} is told that other guests ran into the same problem""") { (name: String) =>
    val page = World.guest(name).page
    within(30, s"$name's error page does not say that other guests ran into the same problem")(
      message(page).contains(copyOf(page).shared))
  }

  Then("""{word} is taken back into the portal""") { (name: String) =>
    val page = World.guest(name).page
    within(600, s"$name's error page did not hand back to the portal")(
      !page.url.contains("error.html"))
  }

  Then("""{word} is taken back into the portal within {int} seconds""") {
    (name: String, seconds: Int) =>
      val page = World.guest(name).page
      within(seconds.toLong, s"$name's error page did not hand back to the portal")(
        !page.url.contains("error.html"))
  }

  Then("""{word} is shown a code for the staff and no way out""") { (name: String) =>
    val page = World.guest(name).page
    val code =
      await(60, s"$name's error page shows no code for the staff")(
        Option(page.locator("#code")).filter(_.isVisible).map(_.textContent()))
    if !code.matches(UuidText) then
      throw AssertionError(s"$name's error page shows $code for the staff, not an error id")
    if !message(page).contains(copyOf(page).unrecoverable) then
      throw AssertionError(
        s"$name's error page does not ask the guest to show the code: ${message(page)}")
    if page.locator("#action").isVisible then
      throw AssertionError(s"$name's error page offers a way out with the code")
  }
end ErrorRecoverySteps
