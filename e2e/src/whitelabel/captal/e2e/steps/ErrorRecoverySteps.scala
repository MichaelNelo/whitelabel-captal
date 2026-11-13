package whitelabel.captal.e2e.steps

import com.microsoft.playwright.{ConsoleMessage, Page, Request, Response, TimeoutError}
import io.cucumber.scala.{EN, ScalaDsl}
import whitelabel.captal.e2e.backoffice.Locations
import whitelabel.captal.e2e.scenario.World
import whitelabel.captal.mcp.tools.RecoveryTools

/** The way out of an error, as the guest's page shows it: the error page and its countdown, the
  * shortening when other guests run into the same error, reloading the page, and the code staff
  * look an error up by. The error page's copy is its own, not a translation key: it cannot count on
  * the server for it, so it is in English and Spanish by the browser's language, as its script has
  * it.
  *
  * An error is made to happen on demand by the local bundle's `Faults`, through the API's dev
  * endpoints: a server error the API records, or a throw from the portal's own code.
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

  /** Waits until `condition` holds, polling every second, or fails with `what` after `seconds`.
    * `what` is read when it fails, so it says what the page showed then.
    */
  private def within(seconds: Long, what: => String)(condition: => Boolean): Unit =
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

  /** Makes a failure happen in `name`'s portal, `server` or `client`: what the local bundle's
    * `Faults` does on `captal-fault`, through the dev endpoints the local configuration gives every
    * location. The guest's page then leaves for the error page, as it would for a real one.
    */
  private def provoke(name: String, kind: String): Unit =
    val page = World.guest(name).page
    // What the page said and asked meanwhile, for a failure to say what went wrong.
    val seen = scala.collection.mutable.ListBuffer.empty[String]
    val console: java.util.function.Consumer[ConsoleMessage] =
      message => seen += s"console ${message.`type`}: ${message.text}"
    val thrown: java.util.function.Consumer[String] = error => seen += s"page error: $error"
    val failed: java.util.function.Consumer[Request] =
      request => seen += s"failed ${request.method} ${request.url}: ${request.failure}"
    val answered: java.util.function.Consumer[Response] =
      response =>
        if response.url.contains("/api/") then
          seen += s"${response.status} ${response.request.method} ${response.url}"
    page.onConsoleMessage(console)
    page.onPageError(thrown)
    page.onRequestFailed(failed)
    page.onResponse(answered)
    try
      page.evaluate(
        "kind => window.dispatchEvent(new CustomEvent('captal-fault', { detail: kind }))",
        kind)
      page.waitForURL(
        java.util.regex.Pattern.compile(".*/error\\.html.*"),
        Page.WaitForURLOptions().setTimeout(30_000))
    catch
      case _: TimeoutError =>
        throw AssertionError(
          s"$name's page did not leave for the error page on captal-fault '$kind': ${seen.mkString(
              "; ")}")
    finally
      page.offConsoleMessage(console)
      page.offPageError(thrown)
      page.offRequestFailed(failed)
      page.offResponse(answered)
  end provoke

  /** The location's maximum wait, set through the backoffice: every other wait is this one, scaled.
    * Shorter than the default's five minutes, so a scenario waits it out in under one.
    */
  Given(
    """the location {string} makes a new guest wait {int} seconds for an error in its portal's code""") {
    (name: String, seconds: Int) =>
      Locations.setRecovery(
        RecoveryTools.SetRequest(World.location(name).slug, maximumSeconds = Some(seconds)))
  }

  When("""the portal fails for {word} with a server error""") { (name: String) =>
    provoke(name, "server")
  }

  /** This scenario's client error: its own, so an earlier scenario's, or an earlier run's, is not
    * taken for it, which would make it recurrent. One per scenario, as this class is.
    */
  private val clientError = s"client:e2e-${java.util.UUID.randomUUID.toString.take(8)}"

  When("""the portal's code throws for {word}""") { (name: String) =>
    provoke(name, clientError)
  }

  // The same tag: the same throw, from the same place, with the same name, so the same error.
  When("""{word} hits the same error on another phone""") { (name: String) =>
    provoke(name, clientError)
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

  // The server shortens the wait as soon as the feed tells it of the other report, and says so on
  // the page's stream, which reaches the page as it happens: through the distribution in AWS, and
  // through the load balancer in a scenario tagged so here (`World.StreamThroughLoadBalancer`).
  Then("""{word}'s countdown reads at most {int} seconds""") { (name: String, max: Int) =>
    val page = World.guest(name).page
    within(
      60,
      s"$name's countdown reads ${secondsShown(page).getOrElse(
          "nothing")}, not $max seconds or less")(secondsShown(page).exists(_ <= max))
  }

  Then("""{word} is told that other guests ran into the same problem""") { (name: String) =>
    val page = World.guest(name).page
    within(30, s"$name's error page does not say that other guests ran into the same problem")(
      message(page).contains(copyOf(page).shared))
  }

  /** Waits for the error page to hand back to the portal. Through Playwright's own wait: a
    * navigation reaches the page's `url` only while a call into Playwright is under way, and a loop
    * of sleeps would read the error page's address forever.
    */
  private def backInPortal(name: String, seconds: Long): Unit =
    try
      World
        .guest(name)
        .page
        .waitForURL(
          (url: String) => !url.contains("error.html"),
          Page.WaitForURLOptions().setTimeout(seconds * 1000.0))
    catch
      case _: TimeoutError =>
        throw AssertionError(
          s"$name's error page did not hand back to the portal, within $seconds seconds")

  Then("""{word} is taken back into the portal""") { (name: String) =>
    backInPortal(name, 600)
  }

  Then("""{word} is taken back into the portal within {int} seconds""") {
    (name: String, seconds: Int) =>
      backInPortal(name, seconds.toLong)
  }

  /** The way out opened by the portal itself, at once: the error page comes and goes on its own, so
    * it is looked for in the phone's trail rather than on screen.
    */
  Then("""{word} is taken through the error page back into the portal within {int} seconds""") {
    (name: String, seconds: Int) =>
      val who = World.guest(name)
      try
        who
          .page
          .waitForURL(
            (url: String) => !url.contains("error.html") && who.passedThrough("error.html"),
            Page.WaitForURLOptions().setTimeout(seconds * 1000.0))
      catch
        case _: TimeoutError =>
          throw AssertionError(
            s"$name was not taken through the error page back into the portal, within $seconds seconds: ${who
                .trail}")
  }

  // The portal's own error view, not the error page: the location's copy, read as `text <key>`.
  Then("""{word} is told to join the venue's WiFi, with no retry""") { (name: String) =>
    val page = World.guest(name).page
    val shown = page.locator(".error-message")
    within(30, s"$name is not told to join the venue's WiFi: ${shown.textContent()}")(
      shown.textContent() == Locations.text("error.invalidClient"))
    val retry = page.getByText(Locations.text("error.retry"))
    if retry.count() != 1 || retry.isVisible then
      throw AssertionError(s"$name is offered a retry that cannot bring the venue's WiFi")
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
