package whitelabel.captal.mcp

import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.model.{
  CreateBucketRequest,
  HeadObjectRequest,
  PutObjectRequest
}
import whitelabel.captal.mcp.aws.{Asset, AssetStore}
import zio.*
import zio.test.*

/** Exercises the S3 side against LocalStack.
  *
  * The point is not that LocalStack works — it is that this code does, with credentials the signer
  * accepts and the server never validates. Nothing here needs an AWS account, which is what makes
  * the upload flow testable at all.
  *
  * Skipped when LocalStack is not reachable, so the suite stays green on a machine without it
  * rather than failing for a reason that has nothing to do with the change under test.
  */
object AssetStoreLocalStackSpec extends ZIOSpecDefault:
  private val Endpoint = "http://localhost:4566"
  private val Bucket = "captal-assets-test"

  private def reachable: ZIO[Any, Nothing, Boolean] = ZIO
    .attemptBlocking:
      val connection = java
        .net
        .URI
        .create(s"$Endpoint/_localstack/health")
        .toURL
        .openConnection()
        .asInstanceOf[java.net.HttpURLConnection]
      connection.setConnectTimeout(1000)
      connection.setReadTimeout(1000)
      connection.getResponseCode == 200
    .orElseSucceed(false)

  private val store: ZIO[Scope, Throwable, AssetStore] =
    ZIO.attempt:
      // Dummy on purpose: LocalStack does not verify signatures, and the signer only needs a pair
      // to exist.
      java.lang.System.setProperty("aws.accessKeyId", "test")
      java.lang.System.setProperty("aws.secretAccessKey", "test")
      java.lang.System.setProperty("aws.region", "us-east-1")
    *>
      ZIO
        .attempt(AssetStore.s3ClientFor(Some(Endpoint)))
        .flatMap: s3 =>
          ZIO
            .attempt(AssetStore.presignerFor(Some(Endpoint)))
            .map: presigner =>
              AssetStore(Bucket, "E000000", "https://portal.test", s3, null, presigner)

  private def ensureBucket(s: AssetStore): ZIO[Any, Throwable, Unit] = ensureBucketNamed(
    s,
    s.bucket)

  private def ensureBucketNamed(s: AssetStore, bucket: String): ZIO[Any, Throwable, Unit] =
    ZIO
      .attemptBlocking(s.s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build()))
      .unit
      .ignore

  /** A store over a chosen bucket.
    *
    * Its `cdn` client is `null`, which is safe for everything here that only touches S3 — the store
    * never invalidates on its own, the tools do. [[cdnStore]] is the one that needs a real client.
    */
  private def storeFor(bucket: String): ZIO[Scope, Throwable, AssetStore] = store.map(base =>
    base.copy(bucket = bucket))

  /** A store with a real CloudFront client and the distribution the fixture created.
    *
    * `None` without a licensed LocalStack: the distribution id comes from the environment and
    * CloudFront is not emulated in the free tier. Callers skip rather than fail.
    */
  private val cdnStore: ZIO[Scope, Throwable, Option[AssetStore]] = ZIO
    .attempt(Option(java.lang.System.getenv("CLOUDFRONT_DISTRIBUTION_ID")))
    .flatMap:
      case None | Some("") =>
        ZIO.none
      case Some(id) =>
        for
          base <- store
          cdn  <-
            ZIO.acquireRelease(ZIO.attempt(AssetStore.cdnClientFor(Some(Endpoint))))(c =>
              ZIO.succeed(c.close()))
        yield Some(base.copy(distributionId = id, cdn = cdn))

  /** Writes an object the way `client.publishS3` does, metadata included.
    *
    * `Content-Encoding` is the field the copy has to carry across: the bundle's hashed assets are
    * gzipped, and one that arrives without it is bytes the browser will not read.
    */
  private def putRaw(
      s: AssetStore,
      key: String,
      body: String,
      contentType: String,
      encoding: Option[String]): ZIO[Any, Throwable, Unit] = ZIO.attemptBlocking:
    val request = PutObjectRequest.builder().bucket(s.bucket).key(key).contentType(contentType)
    s.s3
      .putObject(
        encoding.fold(request)(request.contentEncoding).build(),
        RequestBody.fromString(body))
    ()

  private def headOf(s: AssetStore, key: String): ZIO[Any, Throwable, (String, Option[String])] =
    ZIO.attemptBlocking:
      val head = s.s3.headObject(HeadObjectRequest.builder().bucket(s.bucket).key(key).build())
      (head.contentType(), Option(head.contentEncoding()))

  def spec =
    suite("AssetStore against LocalStack")(
      test("writes, reads back and reports presence — with credentials AWS never sees"):
        ZIO.scoped:
          for
            up     <- reachable
            result <-
              if !up then
                ZIO.succeed(assertCompletes)
              else
                for
                  s       <- store
                  _       <- ensureBucket(s)
                  written <- s
                    .put("demo", Asset.Styles, ":root { --color-primary: #f0f; }")
                    .mapError(new RuntimeException(_))
                  readBack <- s.get("demo", Asset.Styles).mapError(new RuntimeException(_))
                  present <- s.exists(s.key("demo", Asset.Styles)).mapError(new RuntimeException(_))
                  missing <- s.exists("demo/nothing-here.mp4").mapError(new RuntimeException(_))
                  signed  <- s
                    .presignPut("demo/videos/v1", 15.minutes)
                    .mapError(new RuntimeException(_))
                yield assertTrue(
                  // Gzipped on the way out and back, which is what the browser expects.
                  written > 0,
                  readBack.contains(":root { --color-primary: #f0f; }"),
                  present,
                  !missing,
                  // Path-style, or the browser would resolve a bucket subdomain that does not exist.
                  signed.startsWith(s"$Endpoint/$Bucket/"),
                  signed.contains("X-Amz-Signature"),
                  // No content type is bound: the operator has not chosen a file when this is
                  // signed, so there is nothing to bind. The browser's PUT decides what S3 records
                  // and `confirm_video_upload` checks what arrived — a claim made at creation
                  // could only ever have been a guess.
                  signed.contains("X-Amz-SignedHeaders=host"),
                  !signed.contains("content-type")
                )
          yield result
      ,
      test("an invalidation is created and waited out, for real"):
        // Exercises `createInvalidation` and the poll to `Completed`. Against a licensed LocalStack
        // the invalidation completes on the first poll, well inside `invalidateAndWait`'s
        // five-minute bound.
        cdnStore.flatMap:
          case None =>
            ZIO.succeed(assertCompletes)
          case Some(cdn) =>
            for
              _    <- ensureBucket(cdn)
              _    <- cdn.put("demo", Asset.Styles, "body{}").mapError(new RuntimeException(_))
              one  <- cdn.invalidate("demo", Asset.Styles).mapError(new RuntimeException(_))
              many <- cdn.invalidateAndWait("demo").mapError(new RuntimeException(_))
            yield assertTrue(
              // A real id rather than a sentinel, which is what the old assertion checked for.
              one.nonEmpty,
              one != "skipped-no-cdn",
              many.nonEmpty,
              many != one)
      ,
      test("the bundle copy carries metadata across and leaves a custom icon alone"):
        // The step `captal locations push` did first and nothing did after the CLI was deleted.
        // Three things have to hold and none is visible from the object listing: the `.gz` assets
        // keep `Content-Encoding`, without which the browser refuses them; a location that has set
        // its own icon does not get the bundle's default written over it; and running twice does
        // the same thing as running once.
        //
        // That last one is why the icon rule compares ETags instead of asking whether the key
        // exists. The first version of this test passed alone and failed in the full suite: the
        // first run had copied the default icon to `plain/`, so the second saw a key there and
        // reported a location with no branding as having its own. Re-runnability was the symptom;
        // a redeploy never picking up a changed default was the other half of the same bug.
        ZIO.scoped:
          for
            up     <- reachable
            result <-
              if !up then
                ZIO.succeed(assertCompletes)
              else
                for
                  s <- storeFor("captal-assets-bundle-test")
                  _ <- ensureBucketNamed(s, s.bucket)
                  _ <- putRaw(s, "bundle/index.html", "<html>", "text/html", None)
                  _ <- putRaw(
                    s,
                    "bundle/assets/app-abc123.js.gz",
                    "gzipped-bytes",
                    "application/javascript",
                    Some("gzip"))
                  _ <- putRaw(
                    s,
                    "bundle/brand-icon.svg",
                    "<svg>default</svg>",
                    "image/svg+xml",
                    None)

                  // A location with no branding of its own: it takes the default icon too.
                  plain     <- s.copyBundle("plain").mapError(new RuntimeException(_))
                  plainIcon <- s.get("plain", Asset.BrandIcon).mapError(new RuntimeException(_))
                  gz        <- headOf(s, "plain/assets/app-abc123.js.gz")
                  html      <- headOf(s, "plain/index.html")
                  // And again, which a redeploy does. The default it already carries is this copy's
                  // own work, so it is refreshed rather than mistaken for branding.
                  again <- s.copyBundle("plain").mapError(new RuntimeException(_))

                  // A location that has set one: the copy must skip that single key.
                  _ <- s
                    .put("branded", Asset.BrandIcon, "<svg>theirs</svg>")
                    .mapError(new RuntimeException(_))
                  branded     <- s.copyBundle("branded").mapError(new RuntimeException(_))
                  brandedIcon <- s.get("branded", Asset.BrandIcon).mapError(new RuntimeException(_))
                  // Everything else still arrived, so the skip is one key and not the whole copy.
                  brandedHtml <- s.exists("branded/index.html").mapError(new RuntimeException(_))
                yield assertTrue(
                  plain.objects == 3,
                  !plain.iconPreserved,
                  plainIcon.contains("<svg>default</svg>"),
                  // Same answer the second time, which is what makes a redeploy safe and this test
                  // re-runnable.
                  again == plain,
                  // The field the copy exists to preserve.
                  gz == ("application/javascript", Some("gzip")),
                  html == ("text/html", None),
                  branded.objects == 2,
                  branded.iconPreserved,
                  brandedIcon.contains("<svg>theirs</svg>"),
                  brandedHtml
                )
          yield result
      ,
      test("an unpublished bundle copies nothing rather than failing"):
        // Zero is a real answer and a different problem from a failed deploy: the AWS resources are
        // fine and the portal's own page 404s. `deploy_location` turns this into a next step naming
        // the Mill task, which it cannot do if the copy errors instead.
        ZIO.scoped:
          for
            up     <- reachable
            result <-
              if !up then
                ZIO.succeed(assertCompletes)
              else
                for
                  s     <- storeFor("captal-assets-nobundle-test")
                  _     <- ensureBucketNamed(s, s.bucket)
                  empty <- s.copyBundle("whatever").mapError(new RuntimeException(_))
                yield assertTrue(empty.objects == 0, !empty.iconPreserved)
          yield result
    ) @@ TestAspect.sequential
end AssetStoreLocalStackSpec
