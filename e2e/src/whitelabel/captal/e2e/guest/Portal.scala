package whitelabel.captal.e2e.guest

import scala.jdk.CollectionConverters.*

import com.microsoft.playwright.*
import com.microsoft.playwright.options.Cookie
import whitelabel.captal.e2e.backoffice.Locations
import whitelabel.captal.e2e.scenario.{Run, World}
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
    while !serving(location) do
      if System.nanoTime > deadline then
        throw AssertionError(
          s"${location.slug} did not answer through the distribution in 5 minutes")
      Thread.sleep(3000)

  /** Whether a location's API answers its health check through the distribution, now. */
  def serving(location: Locations.Deployed): Boolean = scala
    .util
    .Try(requests.get(location.api("health")))
    .toOption
    .exists(response => response.ok && response.text.contains("ok"))

  /** A guest on a phone of their own, `mac`, sent to the portal by the access point with the
    * fingerprint the link shortener gave the device, `fingerprint`.
    */
  final case class Guest(
      name: String,
      mac: String,
      fingerprint: String,
      location: Locations.Deployed,
      context: BrowserContext,
      page: Page):
    /** The same phone sent to `at`'s portal again, as on a later visit: same cookies, same MAC,
      * same fingerprint, in a new page.
      */
    def returnTo(at: Locations.Deployed = location): Guest =
      val again = context.newPage()
      again.navigate(at.portal(mac, fingerprint))
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
      Portal.close(
        context,
        pages
          .zipWithIndex
          .map((opened, index) =>
            opened -> (
              if pages.size == 1 then
                name
              else
                s"$name-${index + 1}"
            )))
  end Guest

  /** `name` on a new phone set to `locale`, sent by the access point to `location`'s portal. With
    * `streamThroughLoadBalancer`, its event streams go to the load balancer ([[streamThrough]]).
    */
  def arrive(
      name: String,
      location: Locations.Deployed,
      locale: String = "en-US",
      streamThroughLoadBalancer: Boolean = false): Guest =
    val mac = Seq.fill(6)(f"${scala.util.Random.nextInt(256)}%02X").mkString(":")
    val options = Browser
      .NewContextOptions()
      .setIgnoreHTTPSErrors(true)
      .setViewportSize(390, 844)
      .setIsMobile(true)
      .setHasTouch(true)
      .setLocale(locale)
    recorded(options, 390, 844)
    val context = browser.newContext(options)
    if streamThroughLoadBalancer then
      streamThrough(context)
    val page = context.newPage()
    val fingerprint = s"e2e-${java.util.UUID.randomUUID}"
    page.navigate(location.portal(mac, fingerprint))
    Guest(name, mac, fingerprint, location, context, page)
  end arrive

  /** Sends the phone's event streams to the load balancer rather than the distribution, the rest of
    * the portal staying where it is. LocalStack's distribution hands a response on only once it
    * ends, which a stream never does; AWS's hands each part on as it arrives. The request keeps its
    * page, so the page reads it as its own; the browser sends a host only its own cookies, so the
    * session's are copied to the load balancer's host first.
    */
  private def streamThrough(context: BrowserContext): Unit =
    val portal = Run.setting("E2E_PORTAL")
    val balancer = Run.setting("E2E_LOAD_BALANCER")
    val balancerHost = java.net.URI.create(balancer).getHost
    context.route(
      java.util.regex.Pattern.compile(".*/api/recovery/[^/]+/events$"),
      route =>
        val copies = context
          .cookies(route.request.url)
          .asScala
          .map(cookie =>
            Cookie(cookie.name, cookie.value)
              .setDomain(balancerHost)
              .setPath(cookie.path)
              .setSecure(cookie.secure)
              .setHttpOnly(cookie.httpOnly)
              .setSameSite(cookie.sameSite))
        context.addCookies(copies.asJava)
        route.resume(Route.ResumeOptions().setUrl(route.request.url.replace(portal, balancer)))
    )
  end streamThrough

  /** The operator's browser, at a desk rather than on a phone: where the backoffice's pages are
    * shown ([[whitelabel.captal.e2e.backoffice.Operator]]).
    */
  private[e2e] def desk(): BrowserContext =
    val options = Browser.NewContextOptions().setIgnoreHTTPSErrors(true).setViewportSize(760, 900)
    recorded(options, 760, 900)
    browser.newContext(options)

  /** Recorded when the scenario is: written where Playwright likes while the context is in use, and
    * moved by [[close]].
    */
  private def recorded(options: Browser.NewContextOptions, width: Int, height: Int): Unit = World
    .recording
    .foreach(dir =>
      options.setRecordVideoDir(dir.resolve(".raw")).setRecordVideoSize(width, height))

  /** Closes `context`; when the scenario is recorded, each page leaves its video as `<name>.webm`.
    */
  private[e2e] def close(context: BrowserContext, named: List[(Page, String)]): Unit =
    context.close()
    for
      dir          <- World.recording
      (page, name) <- named
      video        <- Option(page.video())
    do
      video.saveAs(dir.resolve(s"$name.webm"))
      video.delete()
end Portal
