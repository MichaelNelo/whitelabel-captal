package whitelabel.captal.e2e.steps

import java.util.regex.Pattern

import com.microsoft.playwright.options.{AriaRole, WaitForSelectorState}
import com.microsoft.playwright.{Locator, Page}
import io.cucumber.scala.{EN, ScalaDsl}
import whitelabel.captal.e2e.backoffice.{Backoffice, Locations, Operator}
import whitelabel.captal.e2e.infrastructure.Stack
import whitelabel.captal.e2e.scenario.World
import whitelabel.captal.mcp.tools.{AnswerFormat, I18nTools, SurveyTools, VideoTools}

/** The operator changing a deployed location while guests use it: retiring a survey question,
  * taking a video out of rotation, replacing a video's file, setting a translation, and a guest who
  * walks away mid-video. Every scenario has a location of its own, built once per run, so none
  * depends on another's changes. What a guest is shown is read off their own phone; what the
  * operator changed is read back through the backoffice, and the files through the assets bucket.
  */
final class LiveContentSteps extends ScalaDsl with EN:

  /** A location of the feature's own, built the first time a scenario names it. */
  private def own(name: String)(content: Locations.Deployed => Any): Locations.Deployed =
    Locations.once(s"live-content-$name") {
      val location = Locations.create(name)
      content(location)
      Locations.deploy(location)
    }

  private def surveysOf(location: Locations.Deployed): List[SurveyTools.SurveySummary] =
    Backoffice
      .tool[SurveyTools.ListRequest, SurveyTools.ListResponse](
        "list_surveys",
        SurveyTools.ListRequest(slug = Some(location.slug)))
      .surveys

  /** The id of the one advertiser question worded `text` in English. */
  private def advertiserQuestionId(location: Locations.Deployed, text: String): String =
    surveysOf(location)
      .filter(_.videoId.isDefined)
      .flatMap(_.questions)
      .filter(_.text.exists(t => t.locale == "en" && t.value == text)) match
      case List(question) =>
        question.id
      case other =>
        throw AssertionError(
          s"Expected one advertiser question '$text' at ${location.slug}, found ${other.size}")

  /** The id of the one video of `location` titled `title`, retired ones included. */
  private def videoIdTitled(location: Locations.Deployed, title: String): String =
    Backoffice
      .tool[VideoTools.ListVideosRequest, VideoTools.ListVideosResponse](
        "list_videos",
        VideoTools.ListVideosRequest(slug = Some(location.slug), includeRetired = Some(true)))
      .videos
      .filter(_.title == title) match
      case List(video) =>
        video.videoId
      case other =>
        throw AssertionError(
          s"Expected one video titled '$title' at ${location.slug}, found ${other.size}")

  /** Adds a question to the survey that follows `videoId`, appended after the ones it has. */
  private def addSurveyQuestion(location: Locations.Deployed, videoId: String, text: String): Unit =
    val survey = surveysOf(location)
      .find(_.videoId.contains(videoId))
      .getOrElse(throw AssertionError(s"No survey follows video $videoId at ${location.slug}"))
    Backoffice.tool[SurveyTools.CreateQuestionRequest, SurveyTools.CreateQuestionResponse](
      "create_question",
      SurveyTools.CreateQuestionRequest(
        surveyId = survey.id,
        text = Locations.texts(text),
        answer = AnswerFormat.Input(None),
        description = None,
        placeholder = None,
        required = Some(true),
        points = None,
        hierarchyLevel = None
      )
    )

  /** The file the player was given, once the page has one: its `src`, which the page sets before
    * the player fetches anything.
    */
  private def playerSource(page: Page): String =
    val player = page.locator("video[src]").first()
    player.waitFor(
      Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED).setTimeout(60_000))
    player.getAttribute("src")

  /** The survey question the page shows now. */
  private def shownQuestion(page: Page): String =
    val question = page.locator("h2.question-title").first()
    question.waitFor(Locator.WaitForOptions().setTimeout(60_000))
    question.textContent().trim

  /** The bucket the assets live in, which is the one whose name ends in `-assets`. */
  private def assetsBucket: String =
    "\"Name\"\\s*:\\s*\"([^\"]+)\""
      .r
      .findAllMatchIn(Stack.aws("s3api", "list-buckets"))
      .map(_.group(1))
      .toList
      .filter(_.endsWith("-assets")) match
      case List(bucket) =>
        bucket
      case other =>
        throw AssertionError(
          s"Expected one assets bucket in LocalStack, found ${other.mkString(", ")}")

  /** Whether the object at `key` is in the assets bucket. Listing by the exact key, so a key that
    * is the prefix of another does not count.
    */
  private def stored(key: String): Boolean =
    val listing = Stack.aws("s3api", "list-objects-v2", "--bucket", assetsBucket, "--prefix", key)
    Pattern.compile("\"Key\"\\s*:\\s*\"" + Pattern.quote(key) + "\"").matcher(listing).find()

  /** The key of a video's first file. A video's first file takes the video's id, so the file a
    * replacement retires is this key until it is replaced.
    */
  private def firstFileKey(location: Locations.Deployed, title: String): String =
    s"${location.slug}/videos/${videoIdTitled(location, title)}"

  private def awaitDeleted(key: String, minutes: Int): Unit =
    val deadline = System.nanoTime + minutes * 60_000_000_000L
    while stored(key) do
      if System.nanoTime > deadline then
        throw AssertionError(s"$key was still stored $minutes minutes after it was replaced")
      Thread.sleep(2000)

  Given("""the operator's own deployed location {string} with one advertiser video""") {
    (name: String) =>
      World.use(name, own(name)(location => Locations.addAdvertiserVideo(location)))
  }

  Given(
    """the operator's own deployed location {string} whose advertiser video asks two questions""") {
    (name: String) =>
      World.use(
        name,
        own(name) { location =>
          val video = Locations.addAdvertiserVideo(location)
          addSurveyQuestion(location, video, "Would you recommend it?")
        })
  }

  Given("""the operator's own deployed location {string} with two advertiser videos""") {
    (name: String) =>
      World.use(
        name,
        own(name) { location =>
          Locations.addAdvertiserVideo(location)
          Locations.addAdvertiserVideo(location, "E2E second spot", "Did you like the second one?")
        }
      )
  }

  When(
    """the operator retires the survey question {string} of the advertiser video at {string}""") {
    (text: String, at: String) =>
      val location = World.location(at)
      val response = Backoffice.tool[
        SurveyTools.SetQuestionActiveRequest,
        SurveyTools.SetQuestionActiveResponse](
        "set_question_active",
        SurveyTools.SetQuestionActiveRequest(
          questionId = advertiserQuestionId(location, text),
          active = false))
      if response.active || response.remainingActive < 1 then
        throw AssertionError(
          s"Retiring '$text' at ${location
              .slug} answered $response, with the question still asked or none left")
  }

  When("""the operator takes the advertiser video {string} out of rotation at {string}""") {
    (title: String, at: String) =>
      val location = World.location(at)
      val response = Backoffice.tool[
        VideoTools.SetVideoActiveRequest,
        VideoTools.SetVideoActiveResponse](
        "set_video_active",
        VideoTools.SetVideoActiveRequest(videoId = videoIdTitled(location, title), active = false))
      if response.status != "retired" || response.activeAtLocation < 1 then
        throw AssertionError(
          s"Taking '$title' out of rotation at ${location
              .slug} answered $response, with the video still drawn or nothing left in rotation")
  }

  When("""the operator replaces the file of the advertiser video {string} at {string}""") {
    (title: String, at: String) =>
      val location = World.location(at)
      val videoId = videoIdTitled(location, title)
      val (_, confirmed) = Operator.upload[
        VideoTools.ReplaceFileRequest,
        VideoTools.ResumeUploadResponse](
        "replace_video_file",
        VideoTools.ReplaceFileRequest(videoId))
      if !confirmed.replaced then
        throw AssertionError(
          s"The new file of '$title' at ${location.slug} did not replace the old one")
  }

  When("""the operator sets the English text of {string} to {string} at {string}""") {
    (key: String, value: String, at: String) =>
      Backoffice.tool[I18nTools.SetTranslationsRequest, I18nTools.SetTranslationsResponse](
        "set_translations",
        I18nTools.SetTranslationsRequest(
          slug = World.location(at).slug,
          locale = "en",
          entries = List(I18nTools.Translation(key, value))))
  }

  /** Closes the phone's browser with the network already down, so its page cannot send the beacon
    * that would end the playback: the playback stays open on the server until it expires.
    */
  When("""{word} leaves the WiFi while the advertiser video plays""") { (name: String) =>
    val guest = World.guest(name)
    playerSource(guest.page)
    guest.context.setOffline(true)
    guest.close()
  }

  When("""{word} sees the welcome button {string}""") { (name: String, label: String) =>
    World
      .guest(name)
      .page
      .getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName(label))
      .first()
      .waitFor(Locator.WaitForOptions().setTimeout(60_000))
  }

  Then("""{word} is watching the first file of {string} at {string}""") {
    (name: String, title: String, at: String) =>
      val videoId = videoIdTitled(World.location(at), title)
      val source = playerSource(World.guest(name).page)
      if !source.endsWith(s"/$videoId") then
        throw AssertionError(s"$name's player was given $source, not the first file of '$title'")
  }

  Then("""{word} is shown the survey question {string}""") { (name: String, text: String) =>
    val shown = shownQuestion(World.guest(name).page)
    if shown != text then
      throw AssertionError(s"$name is asked '$shown', expected '$text'")
  }

  Then("""{word} is not shown the survey question {string}""") { (name: String, text: String) =>
    val shown = shownQuestion(World.guest(name).page)
    if shown == text then
      throw AssertionError(s"$name is asked the retired question '$text'")
  }

  Then("""{word} is given the new file of {string} at {string}""") {
    (name: String, title: String, at: String) =>
      val videoId = videoIdTitled(World.location(at), title)
      val source = playerSource(World.guest(name).page)
      if source.endsWith(s"/$videoId") then
        throw AssertionError(s"$name's player was given the replaced file $source")
  }

  Then("""the old file of {string} at {string} is still stored""") { (title: String, at: String) =>
    val key = firstFileKey(World.location(at), title)
    if !stored(key) then
      throw AssertionError(s"$key was deleted while a guest's playback of it is open")
  }

  Then("""the old file of {string} at {string} is deleted""") { (title: String, at: String) =>
    awaitDeleted(firstFileKey(World.location(at), title), minutes = 2)
  }

  /** Waits out the playback grace: the file's length plus ten minutes from the playback's start,
    * which is fixed, so this is the real wait.
    */
  Then("""the old file of {string} at {string} is deleted once its playback has expired""") {
    (title: String, at: String) =>
      awaitDeleted(firstFileKey(World.location(at), title), minutes = 13)
  }
end LiveContentSteps
