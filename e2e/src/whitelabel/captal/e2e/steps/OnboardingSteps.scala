package whitelabel.captal.e2e.steps

import com.microsoft.playwright.options.AriaRole
import com.microsoft.playwright.{FrameLocator, Locator, Page}
import io.cucumber.scala.{EN, ScalaDsl}
import whitelabel.captal.e2e.backoffice.{Backoffice, Locations, Operator}
import whitelabel.captal.e2e.guest.Portal
import whitelabel.captal.e2e.scenario.World
import whitelabel.captal.mcp.tools.{AssetTools, I18nTools, PreviewTools}

/** An operator builds a location through the backoffice (its stylesheet, its icon, its texts in
  * Spanish and English, an advertiser video) and deploys it; a guest's portal shows what was built:
  * the brand, the texts in the language the guest has, the video. The steps of
  * `location_onboarding.feature`.
  *
  * The portal starts a new guest in Spanish whatever the phone's language is: the session's locale
  * defaults to `es`, and only the welcome page's selector changes it.
  */
final class OnboardingSteps extends ScalaDsl with EN:
  private val Colour = "#0b6e4f"
  private val IconMarker = "brasa-icon"

  Given(
    """an operator builds the location {string} with its own styles, icon, texts and advertiser video, and deploys it""") {
    (name: String) =>
      World.use(name, Locations.once(s"onboarding-$name")(build(name)))
  }

  Given(
    """an operator builds the location {string} with its own styles, icon, texts and advertiser video, without deploying it""") {
    (name: String) =>
      World.use(name, Locations.once(s"onboarding-draft-$name")(build(name, deployed = false)))
  }

  Then("""the location {string} has no portal yet""") { (name: String) =>
    if Portal.serving(World.location(name)) then
      throw AssertionError(
        s"${World.location(name).slug} has a portal, though it was never deployed")
  }

  When("""{word} opens the portal on a new phone set to {string}""") {
    (name: String, locale: String) =>
      World.arrive(name, locale = locale)
  }

  When("""{word} chooses {string} as the language""") { (name: String, locale: String) =>
    val _ = World.guest(name).page.locator("select.locale-button").selectOption(locale)
  }

  When("""{word} answers the email question with a new address""") { (name: String) =>
    val page = World.guest(name).page
    requireAsked(page, "Your email?")
    answer(page, s"guest-${java.util.UUID.randomUUID}@example.com")
  }

  Then("""{word} is asked the identification question {string}""") {
    (name: String, question: String) =>
      requireAsked(World.guest(name).page, question)
  }

  Then("""{word}'s portal uses the location's stylesheet""") { (name: String) =>
    val colour = settle(primaryColour(World.guest(name).page), _ == Colour)
    if colour != Colour then
      throw AssertionError(
        s"$name's portal has --color-primary '$colour', the location's stylesheet sets '$Colour'")
  }

  Then("""{word}'s portal shows the location's icon""") { (name: String) =>
    val icon = settle(iconText(World.guest(name)), _.contains(IconMarker))
    if !icon.contains(IconMarker) then
      throw AssertionError(s"$name's portal shows an icon without '$IconMarker': ${icon.take(200)}")
  }

  Then("""{word} reads {string} as the welcome title""") { (name: String, title: String) =>
    val shown = settle(textOf(World.guest(name).page, ".welcome-title"), _ == title)
    if shown != title then
      throw AssertionError(s"$name reads '$shown' as the welcome title, expected '$title'")
  }

  Then("""{word} is shown the video {string}""") { (name: String, title: String) =>
    // The intro card names the video while it plays in, for a few seconds after it mounts.
    val shown = settle(textOf(World.guest(name).page, ".video-intro-title"), _ == title, 60)
    if shown != title then
      throw AssertionError(s"$name is shown the video '$shown', expected '$title'")
  }

  /** The location as the operator builds it: stylesheet and icon first, then the texts of each
    * locale, then the video, then the deployment, which waits until the portal answers, when
    * `deployed`. Its titles and its video are named after it.
    */
  private def build(name: String, deployed: Boolean = true): Locations.Deployed =
    val shown = name.capitalize
    val location = Locations.create(name)
    Backoffice.tool[AssetTools.SetStylesRequest, AssetTools.SetResponse](
      "set_location_styles",
      AssetTools.SetStylesRequest(location.slug, s":root {  --color-primary: $Colour; }"))
    Backoffice.tool[AssetTools.SetIconRequest, AssetTools.SetResponse](
      "set_location_icon",
      AssetTools.SetIconRequest(
        location.slug,
        s"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><title>$IconMarker</title><circle cx="32" cy="32" r="30" fill="$Colour"/></svg>"""
      )
    )
    // The two titles differ per locale; every other key keeps the text `Locations.create` wrote.
    translate(
      location,
      "es",
      Map(
        "welcome.title"    -> s"Bienvenidos a $shown",
        "welcome.subtitle" -> s"Conéctese a $shown en tres pasos"))
    translate(
      location,
      "en",
      Map(
        "welcome.title"    -> s"Welcome to $shown",
        "welcome.subtitle" -> s"Connect to $shown in three steps"))
    Locations.addAdvertiserVideo(location, title = s"$shown spot", question = "Did you like it?")
    if deployed then
      Locations.deploy(location)
    else
      location
  end build

  /** Writes `entries` for one locale; keys left out keep their text. */
  private def translate(location: Locations.Deployed, locale: String, entries: Map[String, String])
      : Unit = Backoffice.tool[I18nTools.SetTranslationsRequest, I18nTools.SetTranslationsResponse](
    "set_translations",
    I18nTools.SetTranslationsRequest(
      slug = location.slug,
      locale = locale,
      entries = entries.toList.map((key, value) => I18nTools.Translation(key, value)))
  )

  private def button(page: Page, key: String) = page.getByRole(
    AriaRole.BUTTON,
    Page.GetByRoleOptions().setName(Locations.text(key)))

  private def answer(page: Page, value: String): Unit =
    val _ = page.locator("input").first().fill(value)
    button(page, "question.submit").click()

  /** Waits until the question shown is `question`, failing with what is shown instead. */
  private def requireAsked(page: Page, question: String): Unit =
    val asked = settle(textOf(page, ".question-title"), _ == question)
    if asked != question then
      throw AssertionError(s"Expected the question '$question', the portal shows '$asked'")

  private def textOf(page: Page, selector: String): String =
    val found = page.locator(selector)
    if found.count() == 0 then
      ""
    else
      found.first().textContent().trim

  private def primaryColour(page: Page): String =
    String
      .valueOf(
        page.evaluate(
          "getComputedStyle(document.documentElement).getPropertyValue('--color-primary').trim()"))
      .trim

  /** The brand icon as the guest's image loads it: the `<img>`'s source, resolved against the page,
    * fetched with the guest's own context. Empty when the page has no image yet.
    */
  private def iconText(who: Portal.Guest): String =
    val icons = who.page.locator("img.brand-icon")
    if icons.count() == 0 then
      ""
    else
      val src = icons.first().getAttribute("src")
      val url = java.net.URI.create(who.page.url()).resolve(src).toString
      val response = who.context.request().get(url)
      if response.status() == 200 then
        response.text()
      else
        s"HTTP ${response.status()}"

  /** The page `preview_location` opened in this scenario, and what it answered. */
  private var preview = Option.empty[(Operator.Opened, PreviewTools.PreviewResponse)]

  private def previewed = preview.getOrElse(
    throw IllegalStateException("The operator has not previewed a location in this scenario"))

  private def primaryColour(portal: FrameLocator): String = scala
    .util
    .Try(
      String
        .valueOf(
          portal
            .locator("html")
            .evaluate(
              "root => getComputedStyle(root).getPropertyValue('--color-primary').trim()",
              null,
              Locator.EvaluateOptions().setTimeout(5000)))
        .trim)
    .getOrElse("")

  When("""the operator previews the {word} screen of the location {string}""") {
    (screen: String, name: String) =>
      val (shown, opened) = Operator.open[
        PreviewTools.PreviewRequest,
        PreviewTools.PreviewResponse](
        "preview_location",
        PreviewTools.PreviewRequest(World.location(name).slug, screen = Some(screen)))
      preview = Some(opened -> shown)
  }

  /** What each screen shows once its view is drawn: the loader every screen starts on already
    * carries the stylesheet, so the colour alone says nothing about whether the view came.
    */
  private val Drawn = Map(
    "welcome"  -> ".welcome-steps",
    "question" -> ".question-title",
    "video"    -> "video.video-hero-video",
    "survey"   -> ".question-title",
    "ready"    -> ".ready-view",
    "access"   -> ".welcome-countdown",
    "error"    -> ".error-title"
  )

  Then("""the preview shows what a guest sees on that screen""") { () =>
    val (opened, shown) = previewed
    val selector = Drawn.getOrElse(
      shown.screen,
      throw AssertionError(s"The preview's screen '${shown.screen}' is no screen this knows"))
    try
      opened.app.locator(selector).first().waitFor(Locator.WaitForOptions().setTimeout(60_000))
    catch
      case _: com.microsoft.playwright.TimeoutError =>
        throw AssertionError(
          s"The preview of '${shown.screen}' did not show '$selector' within a minute")
    opened.linger()
  }

  Then("""the preview uses the location's stylesheet""") { () =>
    val (opened, shown) = previewed
    val colour = settle(primaryColour(opened.app), _ == Colour, 60)
    if colour != Colour then
      throw AssertionError(
        s"The preview of '${shown
            .screen}' has --color-primary '$colour', the location's stylesheet sets '$Colour'")
  }

  Then("""the preview reads {string} as the welcome title""") { (title: String) =>
    val welcome = previewed._1.app.locator(".welcome-title")
    val shown = settle(scala.util.Try(welcome.textContent().trim).getOrElse(""), _ == title)
    if shown != title then
      throw AssertionError(s"The preview's welcome screen reads '$shown', expected '$title'")
  }

  /** Polls `read` until `done` holds or `seconds` pass, and returns the last value read. */
  private def settle[A](read: => A, done: A => Boolean, seconds: Int = 30): A =
    val deadline = System.nanoTime + seconds * 1_000_000_000L
    var value = read
    while !done(value) && System.nanoTime < deadline do
      Thread.sleep(250)
      value = read
    value
end OnboardingSteps
