package whitelabel.captal.e2e

import com.microsoft.playwright.*
import zio.json.ast.Json

/** The portal as guests reach it: through the distribution, in a browser. One browser for the run;
  * each guest is a context of its own, which is a device of its own: its own cookies, its own
  * storage.
  *
  * LocalStack serves the distribution at `<id>.cloudfront.localhost.localstack.cloud`, two levels
  * under the name its certificate covers, so certificate errors are ignored.
  */
object Portal:
  private lazy val playwright: Playwright =
    val started = Playwright.create()
    sys.addShutdownHook(started.close())
    started

  private lazy val browser: Browser = playwright
    .chromium()
    .launch(BrowserType.LaunchOptions().setHeadless(true))

  /** Plain requests, outside any guest's session. */
  private lazy val requests: APIRequestContext = playwright
    .request()
    .newContext(APIRequest.NewContextOptions().setIgnoreHTTPSErrors(true))

  /** Waits for a location just deployed to answer its health check through the distribution: its
    * task started, registered behind the load balancer, and the edge forwards to it.
    */
  def awaitServing(location: Locations.Deployed): Unit =
    val deadline = System.nanoTime + 5 * 60 * 1_000_000_000L
    def healthy = scala
      .util
      .Try(requests.get(location.api("health")))
      .toOption
      .exists(response => response.ok && response.text.contains("ok"))
    while !healthy do
      if System.nanoTime > deadline then
        throw AssertionError(
          s"${location.slug} did not answer through the distribution in 5 minutes")
      Thread.sleep(3000)

  /** A guest on a phone of their own, sent to the portal by the access point. */
  final case class Guest(
      name: String,
      location: Locations.Deployed,
      context: BrowserContext,
      page: Page):
    /** The session's phase, as the portal itself asks for it. */
    def phase: String =
      val body = context.request().get(location.api("status")).text()
      Json
        .decoder
        .decodeJson(body)
        .toOption
        .flatMap(_.asObject)
        .flatMap(_.get("phase"))
        .flatMap(_.asString)
        .getOrElse(throw AssertionError(s"$name's status was not readable: $body"))

    def close(): Unit = context.close()
  end Guest

  def arrive(name: String, location: Locations.Deployed): Guest =
    val mac = Seq.fill(6)(f"${scala.util.Random.nextInt(256)}%02X").mkString(":")
    val context = browser.newContext(
      Browser
        .NewContextOptions()
        .setIgnoreHTTPSErrors(true)
        .setViewportSize(390, 844)
        .setIsMobile(true)
        .setHasTouch(true)
        .setLocale("en-US"))
    val page = context.newPage()
    page.navigate(location.portal(mac, s"e2e-${java.util.UUID.randomUUID}"))
    Guest(name, location, context, page)
end Portal
