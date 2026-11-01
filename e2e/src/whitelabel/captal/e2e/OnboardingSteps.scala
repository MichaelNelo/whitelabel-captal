package whitelabel.captal.e2e

import com.microsoft.playwright.Page
import com.microsoft.playwright.options.AriaRole
import io.cucumber.scala.{EN, ScalaDsl}
import whitelabel.captal.mcp.tools.{AssetTools, I18nTools}

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
    * locale, then the video, then the deployment, which waits until the portal answers.
    */
  private def build(name: String): Locations.Deployed =
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
        "welcome.title"    -> "Bienvenidos a Brasa",
        "welcome.subtitle" -> "Conéctese a Brasa en tres pasos"))
    translate(
      location,
      "en",
      Map(
        "welcome.title"    -> "Welcome to Brasa",
        "welcome.subtitle" -> "Connect to Brasa in three steps"))
    Locations.addAdvertiserVideo(location, title = "Brasa spot", question = "Did you like it?")
    Locations.deploy(location)
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

  /** Polls `read` until `done` holds or `seconds` pass, and returns the last value read. */
  private def settle[A](read: => A, done: A => Boolean, seconds: Int = 30): A =
    val deadline = System.nanoTime + seconds * 1_000_000_000L
    var value = read
    while !done(value) && System.nanoTime < deadline do
      Thread.sleep(250)
      value = read
    value
end OnboardingSteps
