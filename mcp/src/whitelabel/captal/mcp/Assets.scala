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
  HeadObjectRequest,
  NoSuchKeyException,
  PutObjectRequest
}
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import zio.*

/** One asset a location can override, and how it has to be stored.
  *
  * The content type and encoding are not incidental: the SPA fetches these by fixed relative name,
  * so a stylesheet served without `Content-Encoding: gzip` arrives as bytes the browser will not
  * read, and an icon served as anything but `image/svg+xml` does not render.
  */
enum Asset(val fileName: String, val contentType: String, val gzipped: Boolean):
  case Styles    extends Asset("custom-styles.css.gz", "text/css", true)
  case BrandIcon extends Asset("brand-icon.svg", "image/svg+xml", false)

/** S3 and CloudFront access for per-location assets.
  *
  * Credentials are never configured: the container runs with an ECS task role and the SDK's default
  * provider chain picks it up. The permissions that role needs are tracked in `docs/MCP_IAM.md`,
  * which has to grow with every operation added here.
  */
final case class AssetStore(
    bucket: String,
    distributionId: String,
    publicBaseUrl: String,
    s3: S3Client,
    cdn: CloudFrontClient,
    presigner: S3Presigner):

  /** The key the SPA asks for. Its `index.html` is served from under the slug and references these
    * by relative name, so each object has to sit beside it under the same prefix.
    */
  def key(slug: String, asset: Asset): String = s"$slug/${asset.fileName}"

  def put(slug: String, asset: Asset, content: String): ZIO[Any, String, Int] =
    val raw = content.getBytes("UTF-8")
    val body =
      if asset.gzipped then
        gzip(raw)
      else
        raw
    ZIO
      .attemptBlocking:
        val request = PutObjectRequest
          .builder()
          .bucket(bucket)
          .key(key(slug, asset))
          .contentType(asset.contentType)
        val withEncoding =
          if asset.gzipped then
            request.contentEncoding("gzip")
          else
            request
        s3.putObject(withEncoding.build(), RequestBody.fromBytes(body))
      .mapError(e => s"Could not write ${key(slug, asset)}: ${e.getMessage}")
      .as(body.length)
  end put

  def get(slug: String, asset: Asset): ZIO[Any, String, Option[String]] = ZIO
    .attemptBlocking:
      val bytes = s3
        .getObject(GetObjectRequest.builder().bucket(bucket).key(key(slug, asset)).build())
        .readAllBytes()
      Some(
        if asset.gzipped then
          gunzip(bytes)
        else
          new String(bytes, "UTF-8"))
    .catchSome { case _: NoSuchKeyException =>
      ZIO.none
    }
    .mapError(e => s"Could not read ${key(slug, asset)}: ${e.getMessage}")

  /** Without this the edge keeps serving the previous file until the cache expires, so a write
    * would look applied in the tool and be invisible in the portal.
    *
    * `captal locations push` does invalidate, but the whole `<slug>` prefix, because it has just
    * synced the bundle. Writing one file needs neither that reach nor the deploy.
    */
  def invalidate(slug: String, asset: Asset): ZIO[Any, String, String] = ZIO
    .attemptBlocking:
      // A caller reference must be unique per request; the key plus the clock is enough, since two
      // invalidations of the same object in the same millisecond are indistinguishable anyway.
      val reference = s"mcp-$slug-${asset.fileName}-${java.lang.System.currentTimeMillis()}"
      cdn
        .createInvalidation(
          CreateInvalidationRequest
            .builder()
            .distributionId(distributionId)
            .invalidationBatch(
              InvalidationBatch
                .builder()
                .callerReference(reference)
                .paths(CfPaths.builder().quantity(1).items(s"/${key(slug, asset)}").build())
                .build())
            .build())
        .invalidation()
        .id()
    .mapError(e => s"Wrote ${asset.fileName} but could not invalidate the CDN: ${e.getMessage}")

  /** What the SPA fetches. The row stores this rather than the key, because the player needs a URL
    * and the portal has no way to turn a bucket key into one.
    */
  def publicUrl(key: String): String = s"${publicBaseUrl.stripSuffix("/")}/$key"

  /** The key behind a stored URL.
    *
    * Taken from the URI path rather than by stripping the configured base, so a row written when
    * the portal lived on a different domain still resolves.
    */
  def keyOf(url: String): String = scala
    .util
    .Try(java.net.URI.create(url).getPath.stripPrefix("/"))
    .getOrElse(url)

  /** A URL the browser can PUT the video to directly.
    *
    * Presigned rather than proxied: the bytes never pass through this server, which has neither the
    * memory budget nor a reason to see them. The signature carries the content type, so the upload
    * has to send the same one or S3 rejects it — that is what keeps a caller from storing something
    * else under a key the portal will serve as video.
    */
  def presignPut(key: String, contentType: String, validFor: Duration): ZIO[Any, String, String] =
    ZIO
      .attemptBlocking:
        val request = PutObjectRequest
          .builder()
          .bucket(bucket)
          .key(key)
          .contentType(contentType)
          .build()
        presigner
          .presignPutObject(
            PutObjectPresignRequest
              .builder()
              .signatureDuration(java.time.Duration.ofSeconds(validFor.toSeconds))
              .putObjectRequest(request)
              .build())
          .url()
          .toString
      .mapError(e => s"Could not presign an upload for $key: ${e.getMessage}")

  /** Whether the object is actually there.
    *
    * The video row is written before the upload, so this is what separates "the operator finished"
    * from "the operator walked away" — and it is why the row starts inactive.
    */
  def exists(key: String): ZIO[Any, String, Boolean] = ZIO
    .attemptBlocking:
      s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build())
      true
    .catchSome { case _: NoSuchKeyException =>
      ZIO.succeed(false)
    }
    .mapError(e => s"Could not check $key: ${e.getMessage}")

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
      bucket        <- required("ASSETS_BUCKET")
      distribution  <- required("CLOUDFRONT_DISTRIBUTION_ID")
      publicBaseUrl <- required("PUBLIC_BASE_URL")
      s3  <- ZIO.acquireRelease(ZIO.attempt(S3Client.create()))(c => ZIO.succeed(c.close()))
      cdn <- ZIO.acquireRelease(ZIO.attempt(CloudFrontClient.create()))(c => ZIO.succeed(c.close()))
      presigner <-
        ZIO.acquireRelease(ZIO.attempt(S3Presigner.create()))(p => ZIO.succeed(p.close()))
    yield AssetStore(bucket, distribution, publicBaseUrl, s3, cdn, presigner)

  private def required(name: String): ZIO[Any, Throwable, String] = System
    .env(name)
    .orDie
    .someOrFail(new IllegalStateException(s"$name is not set."))
end AssetStore
