package whitelabel.captal.e2e

import com.microsoft.playwright.Page
import io.cucumber.scala.{EN, ScalaDsl}
import whitelabel.captal.mcp.tools.{AssetTools, I18nTools}

/** Two locations of one deployment, north and south, each with its own brand, texts and video, and
  * what a guest does across them: a guest's brand and video are the location's own, and a phone
  * that has identified at one location is recognised at the other, where a promo of the first never
  * plays. The steps of `several_locations.feature`.
  *
  * North has an advertiser video and a promo; the video comes first, so its promo never plays
  * there. South has only a promo, so a guest there is shown the promo that belongs to south, and a
  * leak of north's promo would show it, since north's was given the earlier place in the rotation.
  */
final class SeveralLocationsSteps extends ScalaDsl with EN:
  final private case class Brand(
      colour: String,
      iconMarker: String,
      welcomeEs: String,
      welcomeEn: String)

  private val Brands = Map(
    "north" -> Brand("#2e7d32", "north-icon", "Bienvenidos a Norte", "Welcome to North"),
    "south" -> Brand("#e65100", "south-icon", "Bienvenidos a Sur", "Welcome to South")
  )

  Given(
    """an operator builds two locations, north and south, each with its own brand, texts and video, and deploys them""") {
    () =>
      World.use("north", Locations.once("several-north")(buildNorth()))
      World.use("south", Locations.once("several-south")(buildSouth()))
  }

  Then("""{word}'s portal shows the brand of {string}""") { (name: String, at: String) =>
    val brand = Brands.getOrElse(at, throw IllegalStateException(s"No brand for $at"))
    val page = World.guest(name).page
    val colour = settle(primaryColour(page), _ == brand.colour)
    if colour != brand.colour then
      throw AssertionError(s"$name's portal at $at has --color-primary '$colour'")
    val icon = settle(iconText(World.guest(name)), _.contains(brand.iconMarker))
    if !icon.contains(brand.iconMarker) then
      throw AssertionError(s"$name's portal at $at shows an icon without '${brand.iconMarker}'")
    // The portal starts in Spanish, whatever the phone's language is.
    val title = settle(textOf(page, ".welcome-title"), _ == brand.welcomeEs)
    if title != brand.welcomeEs then
      throw AssertionError(s"$name reads '$title' at $at, expected '${brand.welcomeEs}'")
  }

  Then(
    """the venue's controller let {word}'s phone in, first for {int} minutes and then for {int} minutes""") {
    (name: String, first: Int, second: Int) =>
      val who = World.guest(name)
      val authorized = Venue.callsFor(who.mac).filter(_.action == "AUTHORIZE_GUEST_ACCESS")
      val minutes = authorized.map(_.timeLimitMinutes)
      val expected = List(Some(first), Some(second))
      if minutes != expected || authorized.exists(_.apiKey != Venue.ApiKey) then
        throw AssertionError(
          s"$name's phone ${who
              .mac} was let in as $authorized, expected $first then $second minutes")
  }

  /** North: the stylesheet, the icon and the texts of its brand, its advertiser video, and a promo
    * the video's presence keeps from being shown. Deployed with the access minutes the default
    * gives.
    */
  private def buildNorth(): Locations.Deployed =
    val location = Locations.create("north")
    brand(location, Brands("north"))
    Locations.addAdvertiserVideo(location, title = "North spot", question = "Did you like it?")
    Locations.addPromo(location, "North promo", priority = 1)
    Locations.deploy(location)

  /** South: its brand, its promo alone, and an access of 30 minutes, so its grant is told apart
    * from north's on the controller's record.
    */
  private def buildSouth(): Locations.Deployed =
    val location = Locations.create("south", accessMinutes = 30)
    brand(location, Brands("south"))
    Locations.addPromo(location, "South promo", priority = 10)
    Locations.deploy(location)

  /** The stylesheet and icon of `brand`, and its welcome title in each locale. */
  private def brand(location: Locations.Deployed, brand: Brand): Unit =
    Backoffice.tool[AssetTools.SetStylesRequest, AssetTools.SetResponse](
      "set_location_styles",
      AssetTools.SetStylesRequest(location.slug, s":root { --color-primary: ${brand.colour}; }"))
    Backoffice.tool[AssetTools.SetIconRequest, AssetTools.SetResponse](
      "set_location_icon",
      AssetTools.SetIconRequest(
        location.slug,
        s"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><title>${brand
            .iconMarker}</title><circle cx="32" cy="32" r="30" fill="${brand.colour}"/></svg>"""
      )
    )
    Backoffice.tool[I18nTools.SetTranslationsRequest, I18nTools.SetTranslationsResponse](
      "set_translations",
      I18nTools.SetTranslationsRequest(
        slug = location.slug,
        locale = "es",
        entries = List(I18nTools.Translation("welcome.title", brand.welcomeEs)))
    )
    Backoffice.tool[I18nTools.SetTranslationsRequest, I18nTools.SetTranslationsResponse](
      "set_translations",
      I18nTools.SetTranslationsRequest(
        slug = location.slug,
        locale = "en",
        entries = List(I18nTools.Translation("welcome.title", brand.welcomeEn)))
    )
  end brand

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

  /** The brand icon as the guest's image loads it. See `OnboardingSteps`. */
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
end SeveralLocationsSteps
