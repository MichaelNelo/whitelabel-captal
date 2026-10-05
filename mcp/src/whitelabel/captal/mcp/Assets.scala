package whitelabel.captal.mcp

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.net.URI
import java.time.Duration as JavaDuration
import java.util.zip.{GZIPInputStream, GZIPOutputStream}

import scala.util.Try

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
    presigner: S3Presigner,
    // LocalStack Community answers every CloudFront call with a 501, so against a local endpoint
    // every asset write would report a failed invalidation even though the object landed. There is
    // no edge cache in front of LocalStack either, making the invalidation meaningless rather than
    // merely unavailable. Defaults to true so that forgetting to pass it can only ever make the
    // server stricter.
    cdnEnabled: Boolean = true):

  /** The origin the upload page PUTs to, which the MCP App has to declare in its CSP or the browser
    * blocks the request. The host defaults `connect-src` to `'self'`, so an undeclared bucket fails
    * at the PUT and nowhere earlier.
    *
    * Taken from a signature rather than rebuilt from the bucket and region, because the shape
    * differs — path-style against a local endpoint, virtual-hosted against AWS — and a guess that
    * is close but wrong fails in exactly the same silent way as no declaration at all.
    */
  def uploadOrigin: ZIO[Any, String, String] = presignPut("csp-probe", 1.minute).map: url =>
    val parsed = URI.create(url)
    val port =
      if parsed.getPort < 0 then
        ""
      else
        s":${parsed.getPort}"
    s"${parsed.getScheme}://${parsed.getHost}$port"

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
          // Matches what `client.publishS3` puts on the bundle. Without it the browser invents a
          // freshness window from the object's age, which grows without bound between edits -- and
          // these two are the assets an operator changes expecting to see the change.
          .cacheControl(AssetStore.CacheControl)
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
  def invalidate(slug: String, asset: Asset): ZIO[Any, String, String] =
    if !cdnEnabled then
      ZIO.succeed("skipped-no-cdn")
    else
      invalidateNow(slug, asset)

  private def invalidateNow(slug: String, asset: Asset): ZIO[Any, String, String] = ZIO
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

  /** The key behind a stored URL. The inverse of [[publicUrl]], and tested as one.
    *
    * Two readings, in order. A URL this configuration produced is undone exactly, by removing the
    * base it was built from — no parsing, no guessing. Anything else falls back to the URI path,
    * which is what lets a row written when the portal lived on another domain still resolve.
    *
    * The fallback has to drop a leading bucket segment, and that is where this went wrong. Against
    * CloudFront the path is the key, which is what it originally assumed; against a single local
    * endpoint the addressing is path-style and the bucket is the first segment, so a key came back
    * as `captal-assets-dev/<slug>/videos/…`. Nothing failed, because that is a perfectly valid key:
    * the upload went there, `exists` found it there, the video was confirmed, and only the portal's
    * GET came back 404 — a half-gigabyte object one level deeper than anything will ever look.
    *
    * Trying the exact base first is what keeps that repair from creating its own bug: a location
    * whose slug happened to match the bucket name would otherwise lose its first segment.
    */
  def keyOf(url: String): String =
    val base = s"${publicBaseUrl.stripSuffix("/")}/"
    if url.startsWith(base) then
      url.stripPrefix(base)
    else
      Try:
        val path = URI.create(url).getPath.stripPrefix("/")
        if path.startsWith(s"$bucket/") then
          path.stripPrefix(s"$bucket/")
        else
          path
      .getOrElse(url)

  /** A URL the browser can PUT the video to directly.
    *
    * Presigned rather than proxied: the bytes never pass through this server, which has neither the
    * memory budget nor a reason to see them. The signature carries the content type, so the upload
    * has to send the same one or S3 rejects it — that is what keeps a caller from storing something
    * else under a key the portal will serve as video.
    */
  /** Signed without binding a Content-Type, so `SignedHeaders` is `host` alone and the browser's
    * PUT decides what S3 records. Binding it would mean knowing the type before the operator has
    * chosen a file, which is not a thing that can be known — the old design took it from a name
    * supplied at creation, which was a claim rather than a fact. `confirm_video_upload` checks what
    * arrived.
    */
  def presignPut(key: String, validFor: Duration): ZIO[Any, String, String] = ZIO
    .attemptBlocking:
      val request = PutObjectRequest.builder().bucket(bucket).key(key).build()
      presigner
        .presignPutObject(
          PutObjectPresignRequest
            .builder()
            .signatureDuration(JavaDuration.ofSeconds(validFor.toSeconds))
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
    val in = new GZIPInputStream(new ByteArrayInputStream(bytes))
    try new String(in.readAllBytes(), "UTF-8")
    finally in.close()
end AssetStore

object AssetStore:
  /** A day, matching the bundle. These assets change only when an operator edits them, and that
    * edit invalidates CloudFront -- so the bound that matters is the one on the browser, which
    * otherwise derives its own from how long the object has sat unchanged.
    */
  private[mcp] val CacheControl = "public, max-age=86400"

  /** Fails startup when the bucket or distribution is missing rather than discovering it on the
    * first write, which would leave an operator holding a stylesheet nobody stored.
    */
  val layer: ZLayer[Any, Throwable, AssetStore] = ZLayer.scoped:
    for
      bucket        <- required("ASSETS_BUCKET")
      distribution  <- required("CLOUDFRONT_DISTRIBUTION_ID")
      publicBaseUrl <- required("PUBLIC_BASE_URL")
      endpoint      <- System.env("AWS_ENDPOINT_URL").orDie
      s3  <- ZIO.acquireRelease(ZIO.attempt(s3ClientFor(endpoint)))(c => ZIO.succeed(c.close()))
      cdn <- ZIO.acquireRelease(ZIO.attempt(CloudFrontClient.create()))(c => ZIO.succeed(c.close()))
      presigner <-
        ZIO.acquireRelease(ZIO.attempt(presignerFor(endpoint)))(p => ZIO.succeed(p.close()))
    yield AssetStore(bucket, distribution, publicBaseUrl, s3, cdn, presigner, endpoint.isEmpty)

  /** `AWS_ENDPOINT_URL` points the S3 client somewhere other than AWS — LocalStack, for testing the
    * upload flow without an account.
    *
    * Path-style addressing comes with it. The default puts the bucket in the hostname, which works
    * against AWS and not against a single local endpoint, where `bucket.localhost` resolves to
    * nothing. The credentials are still required by the signer but never checked: LocalStack does
    * not verify signatures, so any pair does.
    */
  private[mcp] def s3ClientFor(endpoint: Option[String]): S3Client =
    endpoint match
      case Some(url) =>
        S3Client.builder().endpointOverride(URI.create(url)).forcePathStyle(true).build()
      case None =>
        S3Client.create()

  private[mcp] def presignerFor(endpoint: Option[String]): S3Presigner =
    endpoint match
      case Some(url) =>
        S3Presigner
          .builder()
          .endpointOverride(URI.create(url))
          // Without this the signed URL names a host the browser cannot resolve, and the failure
          // shows up as a network error at upload time rather than here.
          .serviceConfiguration(
            software
              .amazon
              .awssdk
              .services
              .s3
              .S3Configuration
              .builder()
              .pathStyleAccessEnabled(true)
              .build())
          .build()
      case None =>
        S3Presigner.create()

  private def required(name: String): ZIO[Any, Throwable, String] = System
    .env(name)
    .orDie
    .someOrFail(new IllegalStateException(s"$name is not set."))
end AssetStore
