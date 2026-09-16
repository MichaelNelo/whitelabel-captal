package whitelabel.captal.mcp

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

  private def ensureBucket(s: AssetStore): ZIO[Any, Throwable, Unit] =
    ZIO
      .attemptBlocking(
        s.s3
          .createBucket(
            software
              .amazon
              .awssdk
              .services
              .s3
              .model
              .CreateBucketRequest
              .builder()
              .bucket(Bucket)
              .build()))
      .unit
      .ignore

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
                    .presignPut("demo/videos/v1.mp4", "video/mp4", 15.minutes)
                    .mapError(new RuntimeException(_))
                yield assertTrue(
                  // Gzipped on the way out and back, which is what the browser expects.
                  written > 0,
                  readBack.contains(":root { --color-primary: #f0f; }"),
                  present,
                  !missing,
                  // Path-style, or the browser would resolve a bucket subdomain that does not exist.
                  signed.startsWith(s"$Endpoint/$Bucket/"),
                  signed.contains("X-Amz-Signature")
                )
          yield result
      ,
      test("skips CDN invalidation when pointed at a local endpoint"):
        // Every client is null: if the skip regressed, this would be a NullPointerException rather
        // than a silent call to a CloudFront that LocalStack answers with a 501.
        val local = AssetStore(Bucket, "E000000", "https://portal.test", null, null, null, false)
        for id <- local.invalidate("demo", Asset.Styles).mapError(new RuntimeException(_))
        yield assertTrue(id == "skipped-no-cdn")
      ,
      test("a real endpoint still invalidates, and says so when it cannot"):
        // The mirror image: with the CDN enabled the null client must blow up, which is what keeps
        // `cdnEnabled` from being flipped to false by default as a convenience.
        val aws = AssetStore(Bucket, "E000000", "https://portal.test", null, null, null, true)
        for failed <- aws.invalidate("demo", Asset.Styles).flip
        yield assertTrue(failed.startsWith("Wrote custom-styles.css.gz but could not invalidate"))
    ) @@ TestAspect.sequential
end AssetStoreLocalStackSpec
