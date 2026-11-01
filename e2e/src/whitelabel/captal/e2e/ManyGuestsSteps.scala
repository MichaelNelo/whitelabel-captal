package whitelabel.captal.e2e

import com.microsoft.playwright.options.{AriaRole, WaitForSelectorState}
import com.microsoft.playwright.{Locator, Page}
import io.cucumber.scala.{EN, ScalaDsl}
import whitelabel.captal.mcp.tools.VideoTools

/** Groups of guests at one location, all at once: the crowd of five, each on a phone of its own,
  * advanced one step at a time so that the portal serves them interleaved. Also the promos a
  * location rotates, per guest. A promo location of its own has three promos and no advertiser's
  * video, which would otherwise be played in place of them.
  */
final class ManyGuestsSteps extends ScalaDsl with EN:
  /** The guests of every crowd step. Names only: each one is a phone of its own. */
  private val crowd = List("Ana", "Ben", "Cy", "Dee", "Eli")

  private def guests: List[Portal.Guest] = crowd.map(World.guest)

  private def button(page: Page, key: String) = page.getByRole(
    AriaRole.BUTTON,
    Page.GetByRoleOptions().setName(Locations.text(key)))

  private def answer(page: Page, value: String): Unit =
    page.locator("input").first().fill(value)
    button(page, "question.submit").click()

  private def watch(page: Page): Unit = page
    .locator(".video-progress-btn.active")
    .click(Locator.ClickOptions().setTimeout(60_000))

  /** Polls the session's phase until it is `phase`, for `seconds` at most. */
  private def awaitPhase(who: Portal.Guest, phase: String, seconds: Int): Unit =
    val deadline = System.nanoTime + seconds * 1_000_000_000L
    while !who.phase.equalsIgnoreCase(phase) do
      if System.nanoTime > deadline then
        throw AssertionError(
          s"${who.name}'s session is ${who.phase}, not $phase after $seconds seconds")
      Thread.sleep(1000)

  /** The email a guest's phone answers with, from its own click id: new for every run and guest. */
  private def addressOf(who: Portal.Guest): String =
    s"${who.name}.${who.clickId.stripPrefix("e2e-").take(8)}@example.com"

  /** A promo of `location`, at `priority` in its rotation. Uploaded and confirmed, so in rotation.
    */
  private def createPromo(location: Locations.Deployed, title: String, priority: Int): Unit =
    val video = Backoffice.tool[VideoTools.CreatePromoRequest, VideoTools.CreatePromoResponse](
      "create_promo_video",
      VideoTools.CreatePromoRequest(
        slug = location.slug,
        title = Locations.texts(title),
        description = None,
        minWatchSeconds = None,
        priority = Some(priority),
        noRepeatSeconds = None,
        questions = None)
    )
    Locations.upload(video.uploadUrl)
    Backoffice.tool[VideoTools.ConfirmUploadRequest, VideoTools.ConfirmUploadResponse](
      "confirm_video_upload",
      VideoTools.ConfirmUploadRequest(video.videoId, 6))

  /** The id of the promo titled `title` at `location`, as the backoffice lists it. */
  private def promoId(location: Locations.Deployed, title: String): String = Backoffice
    .tool[VideoTools.ListVideosRequest, VideoTools.ListVideosResponse](
      "list_videos",
      VideoTools.ListVideosRequest(slug = Some(location.slug), includeRetired = None))
    .videos
    .find(video => video.kind == "promo" && video.title == title)
    .map(_.videoId)
    .getOrElse(throw AssertionError(s"No promo titled $title at ${location.slug}"))

  /** A location of its own, one minute of access, its three promos at priorities 1 to 3. */
  private def promoLocation(name: String): Locations.Deployed =
    Locations.once(s"many-guests-$name"):
      val location = Locations.create(name, accessMinutes = 1)
      List("Alpha", "Bravo", "Charlie")
        .zipWithIndex
        .foreach: (title, index) =>
          createPromo(location, title, priority = index + 1)
      Locations.deploy(location)

  Given("""a deployed location {string} whose venue has three promos, in rotation order""") {
    (name: String) =>
      World.use(name, promoLocation(name))
  }

  When("""the crowd opens the portal on new phones""") { () =>
    crowd.foreach(name => World.arrive(name))
  }

  When("""the crowd starts""") { () =>
    guests.foreach(who => button(who.page, "welcome.button.start").click())
  }

  When("""the crowd answers the email question with an address of their own""") { () =>
    guests.foreach(who => answer(who.page, addressOf(who)))
  }

  When("""the crowd answers the question shown with {string}""") { (value: String) =>
    guests.foreach(who => answer(who.page, value))
  }

  When("""the crowd watches the video to the end""") { () =>
    guests.foreach(who => watch(who.page))
  }

  When("""the crowd answers the video's survey with {string}""") { (value: String) =>
    guests.foreach(who => answer(who.page, value))
  }

  When("""the crowd comes back to the portal on the same phones""") { () =>
    crowd.foreach(name => World.keep(World.guest(name).returnTo()))
  }

  When("""the crowd waits for its access to run out""") { () =>
    guests.foreach(who => awaitPhase(who, "welcome", 180))
  }

  Then("""the crowd has internet access""") { () =>
    guests.foreach(who => awaitPhase(who, "authorized", 120))
  }

  Then("""the venue's controller let each of the crowd's phones in once for {int} minutes""") {
    (minutes: Int) =>
      guests.foreach: who =>
        val calls = Venue.callsFor(who.mac)
        val authorized = calls.filter(_.action == "AUTHORIZE_GUEST_ACCESS")
        if authorized.size != 1 then
          throw AssertionError(
            s"${who.name}'s phone ${who.mac} was authorized ${authorized.size} times: $calls")
        val call = authorized.head
        if call.timeLimitMinutes != Some(minutes) || call.apiKey != Venue.ApiKey then
          throw AssertionError(s"${who.name}'s phone was authorized as $call")
  }

  /** The promo a guest's player is playing is the one with `title`: its file is the promo's. */
  Then("""each of the crowd is shown the promo {string}""") { (title: String) =>
    val videoId = promoId(World.location, title)
    guests.foreach: who =>
      val player = who.page.locator("video").first()
      player.waitFor(
        Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED).setTimeout(30_000))
      val source = player.getAttribute("src")
      if source == null || !source.contains(videoId) then
        throw AssertionError(s"${who.name} was shown $source, not the promo $title ($videoId)")
  }
end ManyGuestsSteps
