package whitelabel.captal.e2e.steps

import com.microsoft.playwright.Page
import io.cucumber.scala.{EN, ScalaDsl}
import whitelabel.captal.e2e.backoffice.Locations
import whitelabel.captal.e2e.guest.Portal
import whitelabel.captal.e2e.infrastructure.Stack
import whitelabel.captal.e2e.scenario.{Run, World}
import zio.json.ast.Json

import scala.jdk.CollectionConverters.*

/** A release of the build as the operator's ECS services and the guests' browsers show it, and the
  * change data capture relay restarting under a guest's playback. A release is `ci.run`, which no
  * scenario can run (its Mill command holds the lock the features need), so its scenario is pending
  * and its steps that only observe are implemented for what they read. The relay's scenario needs
  * no fault: a restart is an operator's own act, and what follows it is the playback's end, which
  * reaches the backoffice only through the relay.
  */
final class ReleaseSteps extends ScalaDsl with EN:

  /** A location of the feature's own: created, given its advertiser video and deployed. */
  private def own(name: String): Locations.Deployed =
    Locations.once(s"release-$name") {
      val location = Locations.create(name)
      Locations.addAdvertiserVideo(location)
      Locations.deploy(location)
    }

  /** The JSON an AWS CLI call printed, which a call that failed never does. */
  private def parse(text: String): Json = Json
    .decoder
    .decodeJson(text)
    .toOption
    .getOrElse(throw AssertionError(s"The AWS CLI printed no JSON: $text"))

  private def pick(value: Json, names: String*): Option[Json] =
    names.foldLeft(Option(value))((found, name) => found.flatMap(_.asObject).flatMap(_.get(name)))

  private def items(value: Option[Json]): List[Json] = value
    .flatMap(_.asArray)
    .map(_.toList)
    .getOrElse(Nil)

  private def textOf(value: Option[Json]): Option[String] = value.flatMap(_.asString)

  private def numberOf(value: Option[Json]): Option[Int] = value
    .flatMap(_.asNumber)
    .map(_.value.intValue)

  /** The bucket the assets live in, which is the one whose name ends in `-assets`. */
  private def assetsBucket: String =
    val buckets = items(pick(parse(Stack.aws("s3api", "list-buckets")), "Buckets"))
      .flatMap(bucket => textOf(pick(bucket, "Name")))
      .filter(_.endsWith("-assets"))
    buckets match
      case List(bucket) =>
        bucket
      case other =>
        throw AssertionError(
          s"Expected one assets bucket in LocalStack, found ${other.mkString(", ")}")

  /** The location's ECS service, as `describe-services` names it: `captal-<slug>`. */
  private def serviceNamed(name: String): Json = items(
    pick(
      parse(Stack.aws("ecs", "describe-services", "--cluster", Stack.cluster, "--services", name)),
      "services")).headOption.getOrElse(throw AssertionError(s"No service $name in the cluster"))

  private def locationService(name: String): Json = serviceNamed(
    s"captal-${World.location(name).slug}")

  /** The containers of a task definition, each with its name, image and environment. */
  private def containersOf(taskDefinition: String): List[Json] = items(
    pick(
      parse(Stack.aws("ecs", "describe-task-definition", "--task-definition", taskDefinition)),
      "taskDefinition",
      "containerDefinitions"))

  private def environmentValue(container: Json, name: String): Option[String] = items(
    pick(container, "environment"))
    .find(variable => textOf(pick(variable, "name")).contains(name))
    .flatMap(variable => textOf(pick(variable, "value")))

  /** The API image the backoffice gives the tasks it deploys locations with: the `API_IMAGE` in the
    * task definition of the cluster's service that has one. The release sets it, so this is what a
    * location's own service must run once the release has moved it.
    */
  private def backofficeApiImage: String =
    val arns = items(
      pick(parse(Stack.aws("ecs", "list-services", "--cluster", Stack.cluster)), "serviceArns"))
      .flatMap(_.asString)
    val definitions = arns
      .grouped(10)
      .toList
      .flatMap: batch =>
        items(
          pick(
            parse(
              Stack.aws(
                (
                  Seq("ecs", "describe-services", "--cluster", Stack.cluster, "--services") ++ batch
                )*)),
            "services")).flatMap(service => textOf(pick(service, "taskDefinition")))
    definitions
      .iterator
      .flatMap(definition => containersOf(definition).flatMap(environmentValue(_, "API_IMAGE")))
      .nextOption()
      .getOrElse(
        throw AssertionError(
          "No service in the cluster gives its tasks an API_IMAGE: no backoffice"))
  end backofficeApiImage

  /** The client versions published under `releases/client/`, and the newest of them. */
  private def newestRelease: String =
    val versions = items(
      pick(
        parse(
          Stack.aws(
            "s3api",
            "list-objects-v2",
            "--bucket",
            assetsBucket,
            "--prefix",
            "releases/client/",
            "--delimiter",
            "/")),
        "CommonPrefixes"))
      .flatMap(prefix => textOf(pick(prefix, "Prefix")))
      .map(_.stripPrefix("releases/client/").stripSuffix("/"))
    if versions.isEmpty then
      throw AssertionError("No client is published under releases/client/")
    versions.maxBy(semver)

  private def semver(version: String): (Int, Int, Int) =
    version.takeWhile(char => char.isDigit || char == '.').split('.').map(_.toInt).toList match
      case List(major, minor, patch) =>
        (major, minor, patch)
      case _ =>
        throw AssertionError(s"$version is not a client version")

  /** The manifest the location's own prefix holds: the release version of the client and the files
    * it is made of, as `copyBundle` copied them there.
    */
  private def manifestOf(guest: Portal.Guest): Json =
    val url = s"${Run.setting("E2E_PORTAL")}/${guest.location.slug}/manifest.json"
    val response = guest.context.request().get(url)
    if !response.ok then
      throw AssertionError(
        s"${guest.name}'s location answered ${response.status()} for its manifest")
    parse(response.text)

  /** The playback this page holds open, which the portal records in `sessionStorage` before the
    * player fetches the file, and marks released once the page leaves it.
    */
  private def playbackHeld(page: Page): Boolean =
    val raw = page.evaluate("() => sessionStorage.getItem('captal.playback')")
    Option(raw)
      .map(_.toString)
      .flatMap(stored => Json.decoder.decodeJson(stored).toOption)
      .flatMap(playback => pick(playback, "released"))
      .flatMap(_.asBoolean)
      .contains(false)

  Given("""a release location {string} of its own, deployed with an advertiser video""") {
    (name: String) =>
      World.use(name, own(name))
  }

  When("""the operator releases the build""") { () =>
    World.pending("A release is `ci.run`, which holds Mill's lock: no step of a feature can run it")
  }

  Then("""the service of {string} runs the image the backoffice deploys""") { (name: String) =>
    val expected = backofficeApiImage
    val taskDefinition = textOf(pick(locationService(name), "taskDefinition")).getOrElse(
      throw AssertionError(s"$name's service names no task definition"))
    val image = containersOf(taskDefinition)
      .find(container => textOf(pick(container, "name")).contains("api"))
      .flatMap(container => textOf(pick(container, "image")))
    if !image.contains(expected) then
      throw AssertionError(
        s"$name's service runs ${image.getOrElse(
            "no api container")}, and the backoffice deploys $expected")
  }

  Then("""the service of {string} is still paused""") { (name: String) =>
    val desired = numberOf(pick(locationService(name), "desiredCount"))
    if !desired.contains(0) then
      throw AssertionError(s"$name's service wants $desired tasks: it is not paused")
  }

  /** The page's scripts, and so the chunks it preloads, are all files of the client its location's
    * manifest describes.
    */
  Then("""{word}'s page runs the client its location serves""") { (name: String) =>
    val guest = World.guest(name)
    val loaded = guest
      .page
      .evaluate(
        "() => [...document.querySelectorAll('script[src], link[rel=modulepreload]')].map(e => e.getAttribute('src') || e.getAttribute('href') || '')")
      .asInstanceOf[java.util.List[?]]
      .asScala
      .toList
      .map(_.toString)
      .filter(_.nonEmpty)
      .map(_.stripPrefix("./").takeWhile(_ != '?'))
    val served = pick(manifestOf(guest), "files")
      .flatMap(_.asObject)
      .map(_.fields.map(_._1).toSet)
      .getOrElse(Set.empty[String])
    val unserved = loaded.filterNot(served.contains)
    if !loaded.contains("main.js") || unserved.nonEmpty then
      throw AssertionError(
        s"$name's page loaded ${loaded.mkString(", ")}, and ${unserved.mkString(
            ", ")} are not files of the client its location serves")
  }

  Then("""{word}'s location serves the newest client release""") { (name: String) =>
    val guest = World.guest(name)
    val served = textOf(pick(manifestOf(guest), "version")).getOrElse(
      throw AssertionError(s"${guest.name}'s manifest names no version"))
    val newest = newestRelease
    if served != newest then
      throw AssertionError(
        s"${guest.name}'s location serves client $served, and the newest release is $newest")
  }

  /** The service's running tasks, by ARN. */
  private def runningTasks(service: String): Set[String] =
    items(
      pick(
        parse(
          Stack.aws(
            "ecs",
            "list-tasks",
            "--cluster",
            Stack.cluster,
            "--service-name",
            service,
            "--desired-status",
            "RUNNING")),
        "taskArns")).flatMap(arn => textOf(Some(arn))).toSet

  /** Restarts the relay's ECS service and waits until it runs as many tasks as it wants, none of
    * them one that ran before the restart. Told by the tasks rather than by a new deployment:
    * LocalStack replaces the tasks within the deployment it has. Ten minutes at most: the relay
    * stops its task before it starts the next, and LocalStack takes minutes to stop one.
    */
  When("""the relay restarts""") { () =>
    val service = Stack.relayService
    val before = runningTasks(service)
    Stack.restart(service)
    val deadline = System.nanoTime + 10 * 60_000_000_000L
    def rolledOut: Boolean =
      val current = serviceNamed(service)
      val desired = numberOf(pick(current, "desiredCount")).getOrElse(0)
      val now = runningTasks(service)
      desired >= 1 && numberOf(pick(current, "runningCount")).contains(desired) &&
      now.size == desired && now.intersect(before).isEmpty
    while !rolledOut do
      if System.nanoTime > deadline then
        throw AssertionError(s"$service did not run a new task in 10 minutes after its restart")
      Thread.sleep(3000)
  }

  When("""{word} pauses the advertiser video part way through""") { (name: String) =>
    val page = World.guest(name).page
    // The video is six seconds long: paused between its start and its last second, before the
    // guest's report that it was watched ends the playback.
    val _ = page.waitForFunction(
      "() => { const v = document.querySelector('video[src]'); return !!v && !v.paused && v.currentTime > 0.2 && v.currentTime < v.duration - 1; }",
      null,
      Page.WaitForFunctionOptions().setTimeout(60_000)
    )
    val _ = page.evaluate("() => document.querySelector('video[src]').pause()")
  }

  Then("""{word}'s playback of the advertiser video is held""") { (name: String) =>
    if !playbackHeld(World.guest(name).page) then
      throw AssertionError(s"$name's page holds no playback of the advertiser video")
  }

  When("""{word} resumes the advertiser video""") { (name: String) =>
    val _ = World.guest(name).page.evaluate("() => document.querySelector('video[src]').play()")
  }
end ReleaseSteps
