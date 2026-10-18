package whitelabel.captal.mcp

import com.jamesward.ziohttp.mcp.client.{
  McpClient,
  McpClientConfig,
  McpClientExtension,
  McpClientExtensions,
  ToolCallOutcome
}
import com.jamesward.ziohttp.mcp.{McpApps, McpAppsClientSettings, McpTasks, TaskStatus}
import io.getquill.*
import io.getquill.jdbczio.Quill
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogGroupsRequest
import software.amazon.awssdk.services.ecs.model.DescribeServicesRequest
import software.amazon.awssdk.services.elasticloadbalancingv2.model.{
  DescribeRulesRequest,
  DescribeTargetGroupsRequest
}
import software.amazon.awssdk.services.s3.model.{
  DeleteObjectRequest,
  GetObjectRequest,
  ListObjectsV2Request,
  NoSuchKeyException,
  PutObjectRequest
}
import whitelabel.captal.core.i18n.{I18n, I18nSchema}
import whitelabel.captal.mcp.access.{Auth, Tokens}
import whitelabel.captal.mcp.aws.{Asset, AssetStore, DeployTargets, InfraStore}
import whitelabel.captal.mcp.resources.Readiness
import whitelabel.captal.mcp.tools.{
  AdvertiserTools,
  AnswerFormat,
  AssetTools,
  DeployTools,
  I18nTools,
  IdentificationCategory,
  LocalizedText,
  LocationTools,
  SurveyTools,
  VideoTools
}
import whitelabel.captal.platform.schema.QuillSqlite
import whitelabel.captal.platform.schema.core.given
import whitelabel.captal.platform.schema.given
import whitelabel.captal.platform.{
  AdvertiserRow,
  AdvertiserVideoRow,
  LocalizedTextRow,
  LocationRow,
  QuestionOptionRow,
  QuestionRow,
  QuestionRuleRow,
  RqliteDataSource,
  SurveyRow,
  VideoStatus
}
import scala.io.Source
import zio.*
import zio.http.{Client, Header, Headers, TestClient}
import zio.json.*
import zio.json.ast.Json
import zio.schema.Schema
import zio.schema.codec.JsonCodec
import zio.test.*

import QuillArrow.->

/** One location taken from an empty database to a portal standing up in AWS, driven as a client.
  *
  * Every other spec here covers one tool against one store. This covers the sequence an operator
  * actually performs, and the joins between halves that no single-tool test can see: that the
  * content a dozen calls wrote is what `get_location_readiness` reads as finished, and that the
  * infrastructure `deploy_location` creates is attached to the location those calls described.
  *
  * It speaks the protocol rather than calling handlers. `TestClient` serves this server's own
  * `statelessRoutes` to a real [[McpClient]] with no socket between them, which is what brings the
  * layers a handler call skips into scope: JSON-RPC dispatch, the auth middleware and its scopes,
  * decoding from the raw JSON a client actually sends rather than from a codec round trip, and —
  * the one that cannot be reached any other way — a tool whose `TaskExecution` makes the server
  * answer with a task handle the client then polls.
  *
  * The database is created and migrated outside this test, by the build, which is the arrangement
  * production has: a location does not provision its own database, and `DB_URL` is a value the
  * server knows in advance and injects into every task definition it registers. Nothing here
  * creates or drops a schema.
  *
  * AWS is LocalStack, licensed — ECS, ELBv2 and CloudFront are not in the free tier's registry at
  * all. The shared infrastructure a deploy attaches to is the build's `localStackInfra` fixture,
  * and the whole spec skips when no cluster is configured, which is the same signal the server uses
  * to decide the deploy tools exist. A machine without a licence runs the rest of the suite and
  * passes this, rather than failing for a reason that has nothing to do with the change under test.
  *
  * Two of the aspects are load-bearing rather than hygiene, and both are consequences of using the
  * real thing instead of a hand-built one:
  *
  *   - `withLiveSystem`, because `ZIOSpecDefault` swaps in `TestSystem`, whose environment is
  *     empty. `AssetStore.layer` and `DeployTargets.layer` read `System.env`, so without it they
  *     fail with "ASSETS_BUCKET is not set" however carefully the build sets it. The other specs
  *     avoid this by building their stores by hand; this one wants the layers.
  *   - `withLiveClock`, because the client polls a task with `ZIO.sleep` and `TestClock` does not
  *     advance unless a test tells it to. Without it `awaitTask` waits for a clock that will never
  *     move, and the symptom is not a failure but a run that never ends.
  *
  * The timeout is there because that is the second time a wait in here hung rather than failed, and
  * a spec that polls should say so itself rather than take the suite's wall clock with it.
  */
object DeployE2ESpec extends ZIOSpecDefault:
  private val Slug = "e2e-cafe"
  private val Locale = "es"

  /** Seeded into this spec's own database and presented as a bearer token on every call.
    *
    * The admin, so it carries every scope — including `infra:deploy`, without which the deploy is
    * refused by the middleware before any handler runs. That refusal is one of the things speaking
    * the protocol is here to be able to observe at all.
    */
  private val Token = "e2e-admin-token"

  /** This spec's own database, named by `E2E_DB_URL` and migrated by the build.
    *
    * Not `RqliteDataSource.layer`, which resolves `DB_URL` through `application.conf` and would put
    * this run back on the database every other spec shares. Three kinds of row here are
    * deployment-wide rather than this location's — advertisers, the identification surveys, and now
    * the operator tokens — so a shared database both makes `get_location_readiness` count what
    * other suites wrote and lets them delete what this one just created. Both were observed:
    * `SurveyToolsSpec` removing a survey between two of these calls, and `AuthSpec` leaving the
    * token this one had seeded unusable.
    *
    * Fails loudly when unset rather than falling back: a silent fallback is how a test that looks
    * isolated stops being so, and the symptom would be an intermittent failure in whichever suite
    * happened to be running beside it.
    */
  private val e2eDatabase: ZLayer[Any, Throwable, javax.sql.DataSource] = ZLayer:
    System
      .env("E2E_DB_URL")
      .orDie
      .someOrFail(
        new IllegalStateException(
          "E2E_DB_URL is not set. The build provides it from `mcp.test.migratedE2eUrl`; running this spec outside Mill needs an rqlite of its own."))
      .map(RqliteDataSource.create)

  /** The arguments as JSON, from the typed request.
    *
    * Encoded from the typed request rather than hand-written JSON: the compiler checks the fields,
    * and the discriminator an `AnswerFormat` travels under is derived by the codec — writing
    * `"kind": "input"` would be a guess at the one field whose mismatch this repo has already paid
    * for once. The client decodes the reply the same way, from its own `Schema`.
    */
  private def args[A](value: A)(using schema: Schema[A]): Json.Obj = Json
    .decoder
    .decodeJson(JsonCodec.jsonEncoder(schema).encodeJson(value, None))
    .toOption
    .flatMap(_.asObject)
    .getOrElse(Json.Obj())

  extension (client: McpClient)
    /** One call, with a failure reported as the tool's own words.
      *
      * `callToolAs` fails with an `McpClientError`, whose `toString` is the whole of what went
      * wrong — a refusal, a decode mismatch, a 401 from the middleware. Labelled, because a
      * sequence of twenty calls that fails at one of them should say which.
      */
    private def tool[A: Schema, B: Schema](name: String, in: A): ZIO[Any, Throwable, B] = client
      .callToolAs[B](name, args(in))
      .mapError(error => new AssertionError(s"$name: $error"))

  private def text(value: String) = LocalizedText(Locale, value)

  private def identificationQuestion(category: IdentificationCategory, surveyId: String) =
    SurveyTools.CreateQuestionRequest(
      surveyId = surveyId,
      text = List(text(s"¿${category.stored}?")),
      answer = AnswerFormat.Input(None),
      description = None,
      placeholder = None,
      required = Some(true),
      points = None,
      hierarchyLevel = None
    )

  /** Every content row, deleted before the run rather than after.
    *
    * Wholesale rather than scoped to the slug, which is what this spec's own database buys. Scoping
    * was the first version and it does not work: advertisers and the identification surveys are
    * deployment-wide, so a second run found the advertiser it had created and `create_advertiser`
    * refused the name. "From zero" has to mean zero, or the counts this asserts are counting
    * whatever the last run left.
    *
    * Before rather than after, because a failure part way through is worth leaving on disk to look
    * at, and because a run has to be able to follow a crashed one.
    *
    * Children first: nothing here relies on a cascade, and SQLite would not give one anyway with
    * foreign keys off.
    */
  private def clearContent: ZIO[QuillSqlite, Throwable, Unit] = ZIO.serviceWithZIO[QuillSqlite]:
    quill =>
      import quill.*
      quill.run(query[QuestionRuleRow].delete) *> quill.run(query[QuestionOptionRow].delete) *>
        quill.run(query[QuestionRow].delete) *> quill.run(query[SurveyRow].delete) *>
        quill.run(query[AdvertiserVideoRow].delete) *> quill.run(query[LocalizedTextRow].delete) *>
        quill.run(query[AdvertiserRow].delete) *> quill.run(query[LocationRow].delete).unit

  /** Something under `bundle/`, so the copy has work to do.
    *
    * Published by `client.publishS3` in a real deployment. Three objects, one of them the default
    * `brand-icon.svg` the bundle really does carry — which is what makes the copy's one special
    * case reachable from here: this location sets an icon of its own, so two objects should be
    * copied and the third skipped.
    */
  private def publishBundle: ZIO[AssetStore, Throwable, Unit] = ZIO.serviceWithZIO[AssetStore]:
    assets =>
      ZIO.attemptBlocking:
        // A neighbour whose slug starts with this one, so the teardown's prefix delete has something
        // to get wrong. Written here rather than in a fixture because it is one object.
        assets
          .s3
          .putObject(
            PutObjectRequest
              .builder()
              .bucket(assets.bucket)
              .key(s"$Slug-centro/index.html")
              .contentType("text/html")
              .build(),
            RequestBody.fromString("neighbour")
          )
        // Cleared first, because the count this asserts is of what the copy found. A previous
        // version of this fixture published `assets/app.js`, and that one object survived into the
        // next run and made the copy report one more than there was.
        assets
          .s3
          .listObjectsV2(
            ListObjectsV2Request.builder().bucket(assets.bucket).prefix("bundle/").build())
          .contents()
          .forEach(obj =>
            assets
              .s3
              .deleteObject(
                DeleteObjectRequest.builder().bucket(assets.bucket).key(obj.key()).build()))
        // The real bundle's shape, including both HTML files and their relative script references —
        // which is what the pause relies on: swapping `index.html` works because the maintenance
        // page asks for `./maintenance.js` and that file is already under the slug.
        List(
          "bundle/index.html" ->
            ("text/html", """<html><script type="module" src="./main.js"></script></html>"""),
          "bundle/maintenance.html" -> (
            "text/html",
            """<html><script type="module" src="./maintenance.js"></script></html>"""),
          "bundle/main.js"        -> ("application/javascript", "// portal"),
          "bundle/maintenance.js" -> ("application/javascript", "// maintenance"),
          "bundle/brand-icon.svg" -> ("image/svg+xml", "<svg>default</svg>")
        ).foreach: (key, meta) =>
          val (contentType, body) = meta
          assets
            .s3
            .putObject(
              PutObjectRequest
                .builder()
                .bucket(assets.bucket)
                .key(key)
                .contentType(contentType)
                .build(),
              RequestBody.fromString(body))

  /** Any key as text, or `None` when it is not there.
    *
    * `AssetStore.get` is typed to the two assets an operator can override, and what this needs to
    * read is the page the edge serves — which is a bundle object, not one of those.
    */
  private def objectText(key: String): ZIO[AssetStore, Throwable, Option[String]] = ZIO
    .serviceWithZIO[AssetStore]: assets =>
      ZIO
        .attemptBlocking(
          Some(
            new String(
              assets
                .s3
                .getObject(GetObjectRequest.builder().bucket(assets.bucket).key(key).build())
                .readAllBytes(),
              "UTF-8")))
        .catchSome { case _: NoSuchKeyException =>
          ZIO.none
        }

  /** This server's own routes, served to a client with nothing between them.
    *
    * Declares both extensions the server does, which is not decoration: without
    * `io.modelcontextprotocol/tasks` on the request the server answers `deploy_location`
    * synchronously — that is exactly what `TaskExecution.WhenSupported` means — and the task path
    * would go untested while appearing to work.
    */
  private def connect(infra: Option[InfraStore])
      : ZIO[QuillSqlite & AssetStore & TestClient & Client & Scope, Throwable, McpClient] =
    for
      _          <- Tokens.seedAdmin(Token, "e2e").mapError(new IllegalStateException(_))
      resource   <- Auth.resourceUri
      env        <- ZIO.environment[QuillSqlite & AssetStore]
      extensions <- CaptalMcp.extensions
      routes = CaptalMcp
        .server(
          Auth.mcpAuth(resource),
          env,
          "https://bucket.e2e.test",
          "https://cdn.e2e.test",
          resource.toString.stripSuffix("/mcp"),
          infra,
          extensions)
        .statelessRoutes
        .provideEnvironment(env)
      _        <- TestClient.addRoutes(routes)
      declared <- ZIO
        .fromEither(
          McpClientExtensions(
            McpClientExtension(McpTasks.Id, Json.Obj()),
            McpApps.clientExtension(McpAppsClientSettings.Html)))
        .mapError(error => new IllegalStateException(s"Client extensions: $error"))
      // How this client answers an `elicitation/create`, which `destroy_location` uses to ask about
      // the one irreversible step. Declines the first and accepts every one after, so a single run
      // of this spec covers both branches — and the decline is what proves the objects survive a
      // teardown that was not told to remove them.
      asked  <- Ref.make(0)
      client <- McpClient
        .connect(
          McpClientConfig(
            serverUrl = resource.toString,
            headers = Headers(Header.Authorization.Bearer(Token)),
            onInputRequest = Some(_ =>
              asked
                .getAndUpdate(_ + 1)
                .map: seen =>
                  Json.Obj(
                    "action" ->
                      Json.Str(
                        if seen == 0 then
                          "decline"
                        else
                          "accept"),
                    "content" -> Json.Obj("deleteAssets" -> Json.Bool(true))))
          ),
          declared
        )
        .mapError(error => new AssertionError(s"connect: $error"))
    yield client

  /** The content half: everything `get_location_readiness` asks for, in the order it asks. */
  private def buildContent(
      client: McpClient): ZIO[QuillSqlite & AssetStore, Throwable, VideoTools.CreateVideoResponse] =
    for
      _ <- client.tool[LocationTools.CreateRequest, LocationTools.CreateResponse](
        "create_location",
        LocationTools.CreateRequest(Slug, "Café E2E"))

      advertiser <- client
        .tool[AdvertiserTools.CreateRequest, AdvertiserTools.CreateResponse](
          "create_advertiser",
          AdvertiserTools.CreateRequest("Anunciante E2E", 10))
        .map(_.id)

      // All three, because `missingIdentificationSurveys` lists each by name and a guest cannot be
      // identified without the email one. Created outright rather than ensured: this database is
      // this spec's and was just emptied, so there is nothing to find.
      _ <-
        ZIO.foreachDiscard(IdentificationCategory.values.toList): category =>
          client
            .tool[SurveyTools.CreateIdentificationSurveyRequest, SurveyTools.CreateSurveyResponse](
              "create_identification_survey",
              SurveyTools.CreateIdentificationSurveyRequest(
                category,
                Some(s"${category.stored} e2e")))
            .flatMap: survey =>
              // A survey with no active question is as unusable as a missing one: the query that
              // serves identification joins questions, so it returns nothing.
              client.tool[SurveyTools.CreateQuestionRequest, SurveyTools.CreateQuestionResponse](
                "create_identification_question",
                identificationQuestion(category, survey.surveyId))

      // An advertiser's video, created together with the survey it is followed by — a video whose
      // survey has nothing left to ask is never drawn.
      created <- client.tool[VideoTools.CreateVideoRequest, VideoTools.CreateVideoResponse](
        "create_advertiser_video",
        VideoTools.CreateVideoRequest(
          slug = Slug,
          advertiserId = advertiser,
          title = List(text("Spot E2E")),
          description = None,
          minWatchSeconds = Some(5),
          priority = Some(1),
          questions = List(
            VideoTools.VideoQuestion(
              text = List(text("¿Te gustó?")),
              answer = AnswerFormat.Input(None),
              required = Some(false),
              points = Some(1))),
          productCampaignId = Some("campaign-e2e")
        )
      )

      // The file, PUT to the presigned URL the tool just handed out, then confirmed. Until that
      // happens the video is `pending` and out of rotation, which is what `pendingVideos` counts.
      _ <- ZIO.attemptBlocking:
        val connection = java
          .net
          .URI
          .create(created.uploadUrl)
          .toURL
          .openConnection()
          .asInstanceOf[java.net.HttpURLConnection]
        connection.setRequestMethod("PUT")
        connection.setDoOutput(true)
        connection.getOutputStream.write("fake-mp4-bytes".getBytes("UTF-8"))
        connection.getOutputStream.close()
        val status = connection.getResponseCode
        if status / 100 != 2 then
          throw new AssertionError(s"The presigned PUT was refused with $status")
      _ <- client.tool[VideoTools.ConfirmUploadRequest, VideoTools.ConfirmUploadResponse](
        "confirm_video_upload",
        VideoTools.ConfirmUploadRequest(created.videoId, 30))

      // Branding. Readiness reports both as something left to do, and the icon is also what makes
      // the bundle copy's exception apply: an icon the operator set must survive the deploy.
      _ <- client.tool[AssetTools.SetStylesRequest, AssetTools.SetResponse](
        "set_location_styles",
        AssetTools.SetStylesRequest(Slug, ":root { --color-primary: #0a0; }"))
      _ <- client.tool[AssetTools.SetIconRequest, AssetTools.SetResponse](
        "set_location_icon",
        AssetTools.SetIconRequest(
          Slug,
          """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 8 8"><rect width="8" height="8"/></svg>""")
      )

      // Every key the portal requires, derived from the type rather than listed — which is how the
      // tool advertises them and the only way this stays right when a key is added.
      _ <- client.tool[I18nTools.SetTranslationsRequest, I18nTools.SetTranslationsResponse](
        "set_translations",
        I18nTools.SetTranslationsRequest(
          slug = Slug,
          locale = Locale,
          entries = I18nSchema.keys[I18n].map(key => I18nTools.Translation(key, s"texto $key")))
      )
    yield created

  /** The whole lifecycle, as one sequence, extracted from the test it is the body of.
    *
    * Not for readability: inline, the typer overflowed its stack on it. Five phases of calls and
    * reads in one for-comprehension inside a `ZIO.scoped` inside a `test` is more nesting than it
    * will carry, and the error says only "Stack overflow in the compiler".
    */
  private def lifecycle(store: InfraStore, infra: Option[InfraStore])
      : ZIO[QuillSqlite & AssetStore & TestClient & Client & Scope, Throwable, TestResult] =
    for
      _      <- clearContent
      _      <- publishBundle
      client <- connect(infra)

      // Discovery, which a handler-level run cannot do at all: the server builds this
      // list per request and the auth middleware decides what the token may see.
      tools <- client.listTools.mapError(error => new AssertionError(s"tools/list: $error"))

      video <- buildContent(client)

      // The content half, read back through the tool an operator would call.
      ready <- client.tool[Readiness.ReadinessRequest, Readiness.LocationReadiness](
        "get_location_readiness",
        Readiness.ReadinessRequest(Slug))

      // Server-directed, and the client declared the extension, so this must come back
      // as a task rather than a result. Observed rather than assumed: `callTool` would
      // follow a task transparently and this would pass either way.
      outcome <- client
        .startTool("deploy_location", args(DeployTools.DeployRequest(Slug)))
        .mapError(error => new AssertionError(s"deploy_location: $error"))
      task =
        outcome match
          case ToolCallOutcome.Started(value) =>
            value
          case ToolCallOutcome.Completed(_) =>
            throw new AssertionError(
              "deploy_location answered synchronously, so the Tasks extension is not in force: either the registry lost McpTasks or the tool's TaskExecution was dropped by a wrapper.")
      finished <- client
        .awaitTask(task.taskId)
        .mapError(error => new AssertionError(s"awaitTask: $error"))
      // Asked again afterwards, because the result and the lifecycle are separate
      // claims: a task can carry a result and still be left `working`, and 0.8.3 could
      // never report `failed` at all because nothing set it.
      settled <- client
        .getTask(task.taskId)
        .mapError(error => new AssertionError(s"tasks/get: $error"))
      deployed = finished
        .structuredContent
        .map(_.toJson)
        .toRight("the finished task carried no structured content")
        .flatMap(JsonCodec.jsonDecoder(summon[Schema[DeployTools.DeployResponse]]).decodeJson)
        .fold(error => throw new AssertionError(s"deploy result: $error"), identity)

      // Again, which is both what a redeploy does and the only history-independent way
      // to observe `created`. The first call's value depends on whether a previous run
      // left the service behind — asserting it was true passed once and never again.
      redeployed <- client.tool[DeployTools.DeployRequest, DeployTools.DeployResponse](
        "deploy_location",
        DeployTools.DeployRequest(Slug))

      // What AWS actually has, asked of AWS rather than read back from the response
      // that claimed to have created it.
      services <- ZIO.attemptBlocking:
        store
          .ecs
          .describeServices(
            DescribeServicesRequest
              .builder()
              .cluster(store.targets.cluster)
              .services(store.targets.serviceName(Slug))
              .build())
          .services()
      targetGroups <- ZIO.attemptBlocking:
        store
          .elb
          .describeTargetGroups(
            DescribeTargetGroupsRequest
              .builder()
              .names(store.targets.targetGroupName(Slug))
              .build())
          .targetGroups()
      rules <- ZIO.attemptBlocking:
        store
          .elb
          .describeRules(
            DescribeRulesRequest.builder().listenerArn(store.targets.listenerArn).build())
          .rules()

      // And the database, which the deploy must not have touched at all.
      rows <- ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        quill.run(query[AdvertiserVideoRow]).map(_.filter(_.id.toString == video.videoId))

      // ── Paused ───────────────────────────────────────────────────────────────────
      // The page the edge serves before any of this, so the swap can be told apart
      // from a location that was always serving maintenance.
      liveBefore <- objectText(AssetStore.indexKey(Slug))
      // Pause declares `TaskExecution.Required`, so it must come back as a task: it
      // waits out a CloudFront invalidation and has no synchronous form.
      pauseOutcome <- client
        .startTool("pause_location", args(DeployTools.PauseRequest(Slug)))
        .mapError(error => new AssertionError(s"pause_location: $error"))
      pauseTask =
        pauseOutcome match
          case ToolCallOutcome.Started(value) =>
            value
          case ToolCallOutcome.Completed(_) =>
            throw new AssertionError(
              "pause_location answered synchronously, but it declares TaskExecution.Required — a client that cannot poll should get -32021 and one that can should get a task.")
      paused <- client
        .awaitTask(pauseTask.taskId)
        .mapError(error => new AssertionError(s"awaitTask(pause): $error"))
        .map(result =>
          JsonCodec
            .jsonDecoder(summon[Schema[DeployTools.PauseResponse]])
            .decodeJson(result.structuredContent.map(_.toJson).getOrElse(""))
            .fold(error => throw new AssertionError(s"pause result: $error"), identity))
      // What the edge now serves for this location, and what it does not.
      pausedIndex        <- objectText(AssetStore.indexKey(Slug))
      savedPage          <- objectText(AssetStore.savedPageKey(Slug))
      perSlugMaintenance <- ZIO
        .serviceWithZIO[AssetStore](_.exists(AssetStore.maintenanceKey(Slug)))
        .mapError(new AssertionError(_))
      pausedCount <- store.desiredCount(Slug).mapError(new AssertionError(_))
      pausedRule  <- store.ruleIsPaused(Slug).mapError(new AssertionError(_))
      // The body itself, because this is the contract the portal reads to tell a pause
      // from an outage. Read off the rule rather than from the response that claimed to
      // have set it.
      pausedBody <- ZIO.attemptBlocking:
        store
          .elb
          .describeRules(
            DescribeRulesRequest.builder().listenerArn(store.targets.listenerArn).build())
          .rules()
          .stream()
          .filter(rule =>
            rule
              .conditions()
              .stream()
              .anyMatch(condition => condition.values().contains(store.targets.pathPattern(Slug))))
          .flatMap(rule => rule.actions().stream())
          .map(action => Option(action.fixedResponseConfig()).map(_.messageBody()))
          .findFirst()
          .orElse(None)
      // Paused writes nothing: the location is still there and still active.
      pausedRows <- ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        quill.run(query[LocationRow]).map(_.filter(_.slug == Slug))

      // ── Resumed ──────────────────────────────────────────────────────────────────
      resumeOutcome <- client
        .startTool("resume_location", args(DeployTools.PauseRequest(Slug)))
        .mapError(error => new AssertionError(s"resume_location: $error"))
      resumeTask =
        resumeOutcome match
          case ToolCallOutcome.Started(value) =>
            value
          case ToolCallOutcome.Completed(_) =>
            throw new AssertionError("resume_location answered synchronously")
      resumed <- client
        .awaitTask(resumeTask.taskId)
        .mapError(error => new AssertionError(s"awaitTask(resume): $error"))
        .map(result =>
          JsonCodec
            .jsonDecoder(summon[Schema[DeployTools.ResumeResponse]])
            .decodeJson(result.structuredContent.map(_.toJson).getOrElse(""))
            .fold(error => throw new AssertionError(s"resume result: $error"), identity))
      resumedIndex <- objectText(AssetStore.indexKey(Slug))
      savedGone    <- ZIO
        .serviceWithZIO[AssetStore](_.exists(AssetStore.savedPageKey(Slug)))
        .mapError(new AssertionError(_))
      resumedCount <- store.desiredCount(Slug).mapError(new AssertionError(_))
      resumedRule  <- store.ruleIsPaused(Slug).mapError(new AssertionError(_))

      // ── Destroyed ────────────────────────────────────────────────────────────────
      // Refused first, because the confirmation is the only thing standing between a
      // typo and a teardown.
      refused <-
        client
          .callToolAs[DeployTools.DestroyResponse](
            "destroy_location",
            args(DeployTools.DestroyRequest(Slug, "not-the-slug")))
          .either
      // Twice, because the question about the S3 objects is answered differently each time — see
      // `answerElicitations`. The first run proves the safe default and the second proves the
      // delete, and together they prove a teardown can follow a teardown.
      declined <- client.tool[DeployTools.DestroyRequest, DeployTools.DestroyResponse](
        "destroy_location",
        DeployTools.DestroyRequest(Slug, Slug))
      iconKept <- ZIO
        .serviceWithZIO[AssetStore](_.get(Slug, Asset.BrandIcon))
        .mapError(new AssertionError(_))
      destroyed <- client.tool[DeployTools.DestroyRequest, DeployTools.DestroyResponse](
        "destroy_location",
        DeployTools.DestroyRequest(Slug, Slug))
      // A deleted service lingers as INACTIVE rather than vanishing, which is the state
      // `upsertService` already knows to create over.
      servicesAfter <- ZIO.attemptBlocking:
        store
          .ecs
          .describeServices(
            DescribeServicesRequest
              .builder()
              .cluster(store.targets.cluster)
              .services(store.targets.serviceName(Slug))
              .build())
          .services()
      rulesAfter <- ZIO.attemptBlocking:
        store
          .elb
          .describeRules(
            DescribeRulesRequest.builder().listenerArn(store.targets.listenerArn).build())
          .rules()
      targetGroupAfter <- ZIO
        .attemptBlocking:
          store
            .elb
            .describeTargetGroups(
              DescribeTargetGroupsRequest
                .builder()
                .names(store.targets.targetGroupName(Slug))
                .build())
            .targetGroups()
            .size()
        // Gone is an exception rather than an empty list, which is also why
        // `ensureTargetGroup` catches it on the way in.
        .catchAll(_ => ZIO.succeed(0))
      // Kept on purpose: the log group holds why the thing was torn down.
      logGroupsAfter <- ZIO.attemptBlocking:
        store
          .logs
          .describeLogGroups(
            DescribeLogGroupsRequest
              .builder()
              .logGroupNamePrefix(store.targets.logGroup(Slug))
              .build())
          .logGroups()
          .size()
      // And the whole point: a destroyed location is still a location.
      rowsAfter <- ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        quill.run(query[LocationRow]).map(_.filter(_.slug == Slug))
      videosAfter <- ZIO.serviceWithZIO[QuillSqlite]: quill =>
        import quill.*
        quill.run(query[AdvertiserVideoRow]).map(_.filter(_.id.toString == video.videoId))
      // And the bucket, which a teardown now empties: the icon and the page go, while the rows
      // that describe them stay.
      iconAfter <- ZIO
        .serviceWithZIO[AssetStore](_.get(Slug, Asset.BrandIcon))
        .mapError(new AssertionError(_))
      indexAfter <- objectText(AssetStore.indexKey(Slug))
      // The neighbour, which must not have gone with it.
      neighbour <- objectText(s"$Slug-centro/index.html")
    yield
      val service = services.get(0)
      val targetGroup = targetGroups.get(0).targetGroupArn()
      val pattern = store.targets.pathPattern(Slug)
      assertTrue(
        // Discovery: the deploy tool is advertised because a cluster is configured, and
        // the token's scopes let this caller see it.
        tools.exists(_.name.value == "deploy_location"),
        tools.exists(_.name.value == "create_location"),
        // Content: one step left, and it is the one no tool call can close. UniFi
        // credentials never travel as tool arguments — they are posted to this server's
        // own page under an HMAC ticket, precisely so a token stays out of the
        // conversation. Asserted exactly rather than as a count, so anything else
        // appearing here fails with its own text.
        ready.nextSteps.size == 1,
        ready.nextSteps.head.startsWith("UniFi is not fully configured"),
        ready.active,
        ready.hasStyles,
        ready.hasIcon,
        ready.advertisers == 1,
        ready.requiredKeys == 39,
        ready.activeVideos == 1,
        ready.pendingVideos == 0,
        ready.adVideos == 1,
        ready.advertiserSurveys == 1,
        ready.strandedAdVideos == 0,
        ready.missingIdentificationSurveys == Nil,
        ready.locales.exists(gap => gap.locale == Locale && gap.missing == 0)
      ) &&
      assertTrue(
        // The task reached a terminal state, and the result is the deploy's own.
        finished.isError.forall(_ == false),
        settled.status == TaskStatus.Completed,
        settled.isTerminal,
        // The bundle copy, which is what makes the portal's own page exist. Two of the
        // Three of the five published: the bundle's default icon is skipped because
        // this location set one, and `maintenance.html` is never copied per location —
        // it would be a public URL at every slug serving a maintenance page to guests
        // of a perfectly healthy portal.
        deployed.bundleObjects == 3,
        deployed.iconPreserved,
        deployed.pathPattern == pattern,
        // Idempotent: the second call updates rather than creating a second anything,
        // and reuses the target group and the rule it found. A fresh task definition
        // revision each time is the one thing that is meant to differ.
        !redeployed.created,
        redeployed.targetGroupArn == deployed.targetGroupArn,
        redeployed.ruleArn == deployed.ruleArn,
        redeployed.serviceArn == deployed.serviceArn,
        redeployed.taskDefinitionArn != deployed.taskDefinitionArn
      ) &&
      assertTrue(
        // Infrastructure, as AWS reports it.
        service.serviceName() == store.targets.serviceName(Slug),
        service.desiredCount() == 1,
        targetGroups.size() == 1,
        // The service is attached to the target group the rule forwards to. Asserted
        // separately because a service created without one is never repaired — a bug
        // the CLI had and never fixed.
        service.loadBalancers().size() == 1,
        service.loadBalancers().get(0).targetGroupArn() == targetGroup,
        // A rule for this location's path, pointing at that same target group. Without
        // it a guest gets the SPA and a 403 from the load balancer on every API call.
        rules
          .stream()
          .anyMatch(rule =>
            rule
              .conditions()
              .stream()
              .anyMatch(condition => condition.values().contains(pattern)) &&
              rule.actions().stream().anyMatch(action => action.targetGroupArn() == targetGroup))
        // The deploy wrote nothing: the video is exactly as the content half left it.
      ) &&
      assertTrue(
        rows.size == 1,
        rows.head.status == VideoStatus.Active,
        rows.head.productCampaignId.contains("campaign-e2e"),

        // Paused: no tasks asked for, and the rule answering rather than forwarding.
        // Both halves matter — at zero tasks a forward rule returns an empty 503, which
        // the portal cannot tell from an outage.
        paused.scaledToZero,
        pausedCount == Some(0),
        pausedRule,
        // The exact body, and that it parses the way the SPA parses it. The two sides
        // have no schema between them: one is a string in a load balancer rule, the
        // other is `ApiClient.isPaused`.
        pausedBody == Some(InfraStore.PausedBody),
        pausedBody
          .flatMap(body => Json.decoder.decodeJson(body).toOption)
          .flatMap(_.asObject)
          .flatMap(_.get("status"))
          .flatMap(_.asString) == Some("paused")
      ) &&
      assertTrue(
        paused.answersWith == InfraStore.PausedBody,
        // And it wrote nothing: still one location, still active.
        pausedRows.size == 1,
        pausedRows.head.isActive == 1,

        // The edge: the maintenance page is what `index.html` now is, the location's own
        // page is kept outside its prefix, and no per-slug `maintenance.html` exists for
        // anyone to request by name.
        pausedIndex.exists(_.contains("maintenance.js")),
        savedPage == liveBefore,
        liveBefore.exists(_.contains("main.js")),
        !perSlugMaintenance,

        // Resumed: the page that was live is back, the saved copy is gone, and the API
        // is forwarding again with one task asked for.
        resumed.pageRestored,
        resumedIndex == liveBefore,
        !savedGone,
        resumedCount == Some(1),
        !resumedRule,
        resumed.targetGroupArn == deployed.targetGroupArn,

        // Destroyed, but only after the confirmation matched.
        refused.isLeft,
        // The first run took the infrastructure and declined the objects — the safe default, and it
        // is a default rather than a special case: an unanswered elicitation decodes as `decline`,
        // so a host that cannot ask keeps them too.
        declined.serviceDeleted,
        declined.rulesDeleted == 1,
        declined.targetGroupDeleted,
        declined.taskDefinitionsDeregistered >= 2,
        declined.drained,
        declined.assetsKept,
        declined.objectsDeleted == 0,
        iconKept.exists(_.contains("<rect")),
        // The second finds the infrastructure gone, which is what makes a teardown safe to re-run,
        // and this time accepts.
        !destroyed.serviceDeleted,
        destroyed.rulesDeleted == 0,
        !destroyed.targetGroupDeleted,
        !destroyed.assetsKept,
        // As AWS reports it: no live service, no rule for this path, no target group.
        !servicesAfter.stream().anyMatch(service => service.status() != "INACTIVE"),
        !rulesAfter
          .stream()
          .anyMatch(rule =>
            rule.conditions().stream().anyMatch(condition => condition.values().contains(pattern))),
        targetGroupAfter == 0,
        // The log group survives, because it says why this happened.
        logGroupsAfter == 1,
        destroyed.logGroupKept == store.targets.logGroup(Slug),
        // The database survives all of it, which is what keeps a teardown reversible: the location,
        // its translations and its surveys are there and `deploy_location` can stand the portal
        // back up.
        rowsAfter.size == 1,
        videosAfter.size == 1,
        // The bucket does not, and this is the half the rows cannot tell you about — the video rows
        // just asserted still say `active` and their files are gone.
        destroyed.objectsDeleted > 0,
        iconAfter.isEmpty,
        indexAfter.isEmpty,
        // A slug that merely starts with this one keeps everything. `cafe/` matching `cafe-centro`
        // is the `familyPrefix` bug in another service, and the trailing slash is the whole defence.
        neighbour.contains("neighbour")
      )
  end lifecycle

  /** Every action LocalStack's policy evaluation refused during this run, as `service:Action`.
    *
    * Read from the container's log because that is where soft mode puts it: `IAM_SOFT_MODE`
    * evaluates each request against the policy and logs the outcome without denying, so without
    * reading this back the evaluation happens and nobody is told. The line is
    * `User: <arn> is not authorized to perform: <service>:<Action> because ...`.
    */
  private val Denial = """is not authorized to perform: ([A-Za-z0-9]+:[A-Za-z0-9]+)""".r

  private val deniedActions: ZIO[Any, Nothing, Set[String]] = ZIO
    .attemptBlocking:
      // `ProcessBuilder` rather than os-lib, which is Mill's and not a dependency of this module.
      val process = new ProcessBuilder("docker", "logs", "localstack-dev")
        .redirectErrorStream(true)
        .start()
      val output = Source.fromInputStream(process.getInputStream).mkString
      process.waitFor()
      Denial.findAllMatchIn(output).map(_.group(1)).toSet
    // A machine with no container, or no licence, has nothing to report rather than a failure —
    // the deploy half of this spec has already skipped itself in that case.
    .orElseSucceed(Set.empty)

  /** Denials that fall outside LocalStack's IAM coverage, rather than a policy missing a
    * permission.
    *
    * Not a shrug: its coverage page enumerates the condition keys it supports — global, tag-based,
    * and service-specific for EC2 and RAM only — and lists the services with enforcement coverage,
    * which does not include ECS. Everything here is gated on a key outside that list, `ecs:cluster`
    * for the service calls and `iam:PassedToService` for `PassRole`, and its own
    * `simulate-principal-policy` shows the consequence directly: each flips from `implicitDeny` to
    * `allowed` purely by supplying the key the real request already carries. `Deploy` does pass
    * `.cluster(...)` on every one of these, and an unconditioned action in the same policy,
    * `ecs:RegisterTaskDefinition`, is allowed either way as the control.
    *
    * This is the set to delete when that coverage grows: at that point `CAPTAL_IAM_SOFT=0` passes
    * and the build stops needing soft mode at all.
    */
  private val KnownEmulationGaps = Set(
    "ecs:CreateService",
    "ecs:UpdateService",
    "ecs:DescribeServices",
    "ecs:DeleteService",
    "ecs:ListServices",
    "ecs:ListTasks",
    "ecs:DescribeTasks",
    "iam:PassRole"
  )

  /** The action [[provokeDenial]] asks for, which has to be refused for any of this to mean
    * anything.
    */
  private val ProvokedDenial = "s3:ListAllMyBuckets"

  /** Asks for something the policy deliberately withholds, so the absence of denials can be told
    * apart from the absence of evaluation.
    *
    * Without this, every assertion here passes just as well with `ENFORCE_IAM=0`: no evaluation
    * produces no denials, and a list that is empty for the wrong reason reads exactly like one
    * empty for the right one. That hole already bit one level down — a container started before
    * enforcement was asked for allowed everything and the suite stayed green, which is why the
    * reuse check in the build compares the IAM settings — and it exists here too unless something
    * is required to fail.
    *
    * `ListBuckets` because it has no effect even when soft mode lets it through: the policy grants
    * the object actions on one bucket and the listing of that bucket, never an account-wide
    * listing. An S3 call rather than an ECS one because S3 is in LocalStack's documented IAM
    * coverage and ECS is not, which is what the gaps above are.
    */
  private val provokeDenial: ZIO[Any, Nothing, Unit] =
    System
      .env("AWS_ENDPOINT_URL")
      .flatMap: endpoint =>
        ZIO.attemptBlocking:
          AssetStore.s3ClientFor(endpoint).listBuckets()
          ()
      .ignore

  def spec =
    suite("a location from zero")(
      test("content, infrastructure, pause, resume, teardown — and the content outlives all of it"):
        ZIO.scoped:
          for
            infra  <- ZIO.service[Option[InfraStore]]
            result <- infra.fold(ZIO.succeed(assertCompletes))(store => lifecycle(store, infra))
          yield result
      ,
      test("the deploy needed no permission the backoffice's IAM policy withholds"):
        // Last, and in this spec rather than one of its own, because what it reports on is what the
        // test above just did. All of it ran as the IAM user `modules/mcp` creates, carrying the
        // same policy the deployed task role carries, so a permission that policy withholds shows
        // up here even though soft mode let the call through.
        //
        // This is the point of enforcing IAM locally at all. Before it the suite passed dummy
        // credentials, LocalStack allowed everything, and this test would have passed against an
        // empty policy.
        for
          _      <- provokeDenial
          denied <- deniedActions
        yield
          val unexpected = denied.diff(KnownEmulationGaps + ProvokedDenial).toSeq.sorted
          assertTrue(denied.contains(ProvokedDenial)) ??
            s"$ProvokedDenial was not refused, so nothing here was being evaluated. The policy does not grant it, which means enforcement is off: check ENFORCE_IAM on the container and that the suite is running as the IAM user rather than a dummy pair." &&
            assertTrue(unexpected.isEmpty) ??
            s"denied and not a known emulation gap: ${unexpected.mkString(
                ", ")}. Either `aws_iam_policy.mcp` in the infrastructure repository withholds it, or the request is reaching a resource the policy does not cover — settle which with `awslocal iam simulate-principal-policy` before widening anything."
    ).provideShared(
      e2eDatabase >>> Quill.Sqlite.fromNamingStrategy(io.getquill.SnakeCase),
      AssetStore.layer,
      DeployTargets.layer,
      InfraStore.layer,
      TestClient.layer) @@ TestAspect.sequential @@ TestAspect.withLiveSystem @@
      TestAspect.withLiveClock @@ TestAspect.timeout(3.minutes)
end DeployE2ESpec
