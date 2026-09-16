package whitelabel.captal.mcp

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.cloudfront.CloudFrontClient
import software.amazon.awssdk.services.cloudfront.model.{
  CreateInvalidationRequest,
  InvalidationBatch,
  Paths as CfPaths
}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{
  GetObjectRequest,
  NoSuchKeyException,
  PutObjectRequest
}
import zio.*

/** S3 and CloudFront access for per-location assets.
  *
  * Credentials are never configured: the container runs with an ECS task role and the SDK's default
  * provider chain picks it up. The permissions that role needs are tracked in `docs/MCP_IAM.md`,
  * which has to grow with every operation added here.
  */
final case class AssetStore(
    bucket: String,
    distributionId: String,
    s3: S3Client,
    cdn: CloudFrontClient):

  /** The key the SPA asks for. `index.html` carries
    * `<link rel="stylesheet" href="./custom-styles.css.gz" onerror="this.remove()">` and is served
    * from `<slug>/`, so the object has to sit beside it under the same prefix.
    */
  def stylesKey(slug: String): String = s"$slug/custom-styles.css.gz"

  def putStyles(slug: String, css: String): ZIO[Any, String, Int] =
    val body = gzip(css.getBytes("UTF-8"))
    ZIO
      .attemptBlocking:
        s3.putObject(
          PutObjectRequest
            .builder()
            .bucket(bucket)
            .key(stylesKey(slug))
            .contentType("text/css")
            .contentEncoding("gzip")
            .build(),
          RequestBody.fromBytes(body))
      .mapError(e => s"Could not write ${stylesKey(slug)}: ${e.getMessage}")
      .as(body.length)

  def getStyles(slug: String): ZIO[Any, String, Option[String]] = ZIO
    .attemptBlocking:
      val bytes = s3
        .getObject(GetObjectRequest.builder().bucket(bucket).key(stylesKey(slug)).build())
        .readAllBytes()
      Some(gunzip(bytes))
    .catchSome { case _: NoSuchKeyException =>
      ZIO.none
    }
    .mapError(e => s"Could not read ${stylesKey(slug)}: ${e.getMessage}")

  /** Without this the edge keeps serving the previous stylesheet until the cache expires, so a
    * write would look applied in the tool and be invisible in the portal. The CLI's asset sync
    * never invalidated, which is why an operator could push a rebrand and see nothing change.
    */
  def invalidate(slug: String): ZIO[Any, String, String] = ZIO
    .attemptBlocking:
      val path = s"/${stylesKey(slug)}"
      // A caller reference must be unique per request; the key plus the clock is enough, since two
      // invalidations of the same object in the same millisecond are indistinguishable anyway.
      val reference = s"mcp-$slug-${java.lang.System.currentTimeMillis()}"
      cdn
        .createInvalidation(
          CreateInvalidationRequest
            .builder()
            .distributionId(distributionId)
            .invalidationBatch(
              InvalidationBatch
                .builder()
                .callerReference(reference)
                .paths(CfPaths.builder().quantity(1).items(path).build())
                .build())
            .build())
        .invalidation()
        .id()
    .mapError(e => s"Wrote the stylesheet but could not invalidate the CDN: ${e.getMessage}")

  private def gzip(bytes: Array[Byte]): Array[Byte] =
    val out = new ByteArrayOutputStream()
    val gz = new GZIPOutputStream(out)
    try gz.write(bytes)
    finally gz.close()
    out.toByteArray

  private def gunzip(bytes: Array[Byte]): String =
    val in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(bytes))
    try new String(in.readAllBytes(), "UTF-8")
    finally in.close()
end AssetStore

object AssetStore:
  /** Fails startup when the bucket or distribution is missing rather than discovering it on the
    * first write, which would leave an operator holding a stylesheet nobody stored.
    */
  val layer: ZLayer[Any, Throwable, AssetStore] = ZLayer.scoped:
    for
      bucket       <- required("ASSETS_BUCKET")
      distribution <- required("CLOUDFRONT_DISTRIBUTION_ID")
      s3  <- ZIO.acquireRelease(ZIO.attempt(S3Client.create()))(c => ZIO.succeed(c.close()))
      cdn <- ZIO.acquireRelease(ZIO.attempt(CloudFrontClient.create()))(c => ZIO.succeed(c.close()))
    yield AssetStore(bucket, distribution, s3, cdn)

  private def required(name: String): ZIO[Any, Throwable, String] = System
    .env(name)
    .orDie
    .someOrFail(new IllegalStateException(s"$name is not set."))
end AssetStore
