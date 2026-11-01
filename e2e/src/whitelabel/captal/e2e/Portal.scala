package whitelabel.captal.e2e

import scala.jdk.CollectionConverters.*

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

  /** A guest on a phone of their own, `mac`, sent to the portal by the access point with the click
    * id that tells the device apart, `clickId`.
    */
  final case class Guest(
      name: String,
      mac: String,
      clickId: String,
      location: Locations.Deployed,
      context: BrowserContext,
      page: Page):
    /** The same phone sent to `at`'s portal again, as on a later visit: same cookies, same MAC,
      * same click id, in a new page.
      */
    def returnTo(at: Locations.Deployed = location): Guest =
      val again = context.newPage()
      again.navigate(at.portal(mac, clickId))
      copy(location = at, page = again)

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

    /** Puts the phone down. When the scenario is recorded, each page it had open leaves its video
      * under the guest's name, `<name>.webm`, or `<name>-<n>.webm` for a guest who came back.
      */
    def close(): Unit =
      val pages = context.pages().asScala.toList
      context.close()
      for
        dir             <- World.recording
        (opened, index) <- pages.zipWithIndex
        video           <- Option(opened.video())
      do
        val file =
          if pages.size == 1 then
            s"$name.webm"
          else
            s"$name-${index + 1}.webm"
        video.saveAs(dir.resolve(file))
        video.delete()
  end Guest

  /** `name` on a new phone set to `locale`, sent by the access point to `location`'s portal. */
  def arrive(name: String, location: Locations.Deployed, locale: String = "en-US"): Guest =
    val mac = Seq.fill(6)(f"${scala.util.Random.nextInt(256)}%02X").mkString(":")
    val options = Browser
      .NewContextOptions()
      .setIgnoreHTTPSErrors(true)
      .setViewportSize(390, 844)
      .setIsMobile(true)
      .setHasTouch(true)
      .setLocale(locale)
    // Written where Playwright likes while the phone is in use, and moved by `close`.
    World
      .recording
      .foreach(dir => options.setRecordVideoDir(dir.resolve(".raw")).setRecordVideoSize(390, 844))
    val context = browser.newContext(options)
    val page = context.newPage()
    val clickId = s"e2e-${java.util.UUID.randomUUID}"
    page.navigate(location.portal(mac, clickId))
    Guest(name, mac, clickId, location, context, page)
end Portal
