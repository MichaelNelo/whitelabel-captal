package whitelabel.captal.mcp.aws

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.net.URI
import java.time.Duration as JavaDuration
import java.util.zip.{GZIPInputStream, GZIPOutputStream}

import scala.jdk.CollectionConverters.*
import scala.util.Try

import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.cloudfront.CloudFrontClient
import software.amazon.awssdk.services.cloudfront.model.{
  CreateInvalidationRequest,
  GetInvalidationRequest,
  InvalidationBatch,
  Paths as CfPaths
}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{
  CopyObjectRequest,
  DeleteObjectRequest,
  GetObjectRequest,
  HeadObjectRequest,
  ListObjectsV2Request,
  MetadataDirective,
  NoSuchKeyException,
  PutObjectRequest
}
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import zio.*
import zio.http.{Client, Request, URL}

/** One asset a location can override, and how it has to be stored. The SPA fetches these by fixed
  * relative name, so the content type and encoding are part of the contract.
  */
enum Asset(val fileName: String, val contentType: String, val gzipped: Boolean):
  case Styles    extends Asset("custom-styles.css.gz", "text/css", true)
  case BrandIcon extends Asset("brand-icon.svg", "image/svg+xml", false)

/** What a bundle copy did.
  *
  * @param objects
  *   how many were written. Zero means the bundle prefix is empty.
  * @param iconPreserved
  *   whether the location's own `brand-icon.svg` was left alone.
  */
final case class BundleCopy(objects: Int, iconPreserved: Boolean)

/** S3 and CloudFront access for per-location assets.
  *
  * Credentials are never configured: the container runs with an ECS task role and the SDK's default
  * provider chain picks it up. The permissions that role needs are tracked in `docs/mcp-iam.adoc`.
  */
final case class AssetStore(
    bucket: String,
    distributionId: String,
    publicBaseUrl: String,
    s3: S3Client,
    cdn: CloudFrontClient,
    presigner: S3Presigner):

  /** The origin the upload page PUTs to, which the MCP App has to declare in its CSP `connect-src`
    * or the browser blocks the request. Taken from a signature, since the shape differs between a
    * path-style local endpoint and virtual-hosted AWS.
    */
  def uploadOrigin: ZIO[Any, String, String] = presignPut("csp-probe", 1.minute).map: url =>
    val parsed = URI.create(url)
    val port =
      if parsed.getPort < 0 then
        ""
      else
        s":${parsed.getPort}"
    s"${parsed.getScheme}://${parsed.getHost}$port"

  /** The key the SPA asks for: `<slug>/<fileName>`, beside the `index.html` that references it by
    * relative name.
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
          // freshness window from the object's age.
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

  /** Invalidates the one key an asset write touched. Without it the edge keeps serving the previous
    * file until the cache expires.
    */
  def invalidate(slug: String, asset: Asset): ZIO[Any, String, String] = invalidateNow(slug, asset)

  /** Invalidates everything under `/<slug>`, for a caller that has just rewritten every file the
    * SPA loads.
    */
  def invalidatePrefix(slug: String): ZIO[Any, String, String] = ZIO
    .attemptBlocking:
      val reference = s"mcp-$slug-bundle-${java.lang.System.currentTimeMillis()}"
      cdn
        .createInvalidation(
          CreateInvalidationRequest
            .builder()
            .distributionId(distributionId)
            .invalidationBatch(
              InvalidationBatch
                .builder()
                .callerReference(reference)
                .paths(CfPaths.builder().quantity(1).items(s"/$slug/*").build())
                .build())
            .build())
        .invalidation()
        .id()
    .mapError(e =>
      s"Copied the bundle to '$slug' but could not invalidate the CDN: ${e.getMessage}")

  /** The shared `bundle/` prefix, copied under one location's own prefix. The portal is fetched
    * from `<slug>/`, so until these objects exist a guest gets a 404 for the page itself.
    *
    * Server-side copies with `MetadataDirective.COPY`, which carries `Content-Type` and
    * `Content-Encoding` across — the bundle's `.gz` files are unreadable without the latter.
    *
    * Idempotent, and safe to re-run after a bundle publish: a copy overwrites what was there.
    *
    * A location's own `brand-icon.svg` is kept rather than overwritten by the bundle's default,
    * decided by an ETag comparison: an untouched copy still matches its source and is refreshed,
    * anything the operator wrote differs and is left alone.
    */
  def copyBundle(slug: String): ZIO[Any, String, BundleCopy] =
    val prefix = "bundle/"
    val iconKey = key(slug, Asset.BrandIcon)
    val bundleIconKey = s"$prefix${Asset.BrandIcon.fileName}"
    for
      bundleIcon <- eTagOf(bundleIconKey)
      ownIcon    <- eTagOf(iconKey)
      // Custom only when both exist and differ.
      iconIsCustom = ownIcon.isDefined && bundleIcon.isDefined && ownIcon != bundleIcon
      sources <- listPrefix(prefix)
      copied  <-
        ZIO.foreach(sources): source =>
          val destination = s"$slug/${source.stripPrefix(prefix)}"
          // Skipped rather than copied and then restored, which would leave a window in which the
          // portal serves the wrong icon.
          if destination == iconKey && iconIsCustom then
            ZIO.succeed(None)
          // The maintenance page is deliberately not copied: a pause installs it *as* `index.html`
          // straight from the bundle, and a per-location copy would be a public URL serving
          // maintenance at every healthy slug.
          else if destination == AssetStore.maintenanceKey(slug) then
            ZIO.succeed(None)
          else
            copyObject(source, destination).as(Some(destination))
      // Deploying a paused location brings it back, so the saved page must go: a later resume
      // would otherwise restore something older than what is now live.
      _ <- deleteIfPresent(AssetStore.savedPageKey(slug))
    yield BundleCopy(copied.flatten.size, iconPreserved = iconIsCustom)
    end for
  end copyBundle

  /** Puts the maintenance page where the portal's own page was, and keeps the original under
    * [[AssetStore.savedPageKey]].
    *
    * The edge serves `<slug>/index.html` for the location and for every SPA route under it, so
    * replacing that one object is the whole of making the edge serve maintenance. The state is
    * which object sits at that key.
    *
    * Both HTML files reference `./main.js`, `./maintenance.js` and `./styles.css` relatively, and
    * all three already sit under `<slug>/`, so the swapped page resolves its own assets with
    * nothing else moved.
    */
  def pausePage(slug: String): ZIO[Any, String, Unit] =
    val live = AssetStore.indexKey(slug)
    for
      // Saved first, or a failed maintenance copy leaves the location with no page at all and
      // nothing to restore it from.
      present <- exists(live)
      _       <- ZIO.when(present)(copyObject(live, AssetStore.savedPageKey(slug)))
      _       <- copyObject(AssetStore.BundleMaintenanceKey, live)
    yield ()

  /** Puts the location's own page back, from the copy [[pausePage]] kept, so a pause and a resume
    * are exactly reversible. A bundle published in between is taken by `deploy_location`, not here.
    */
  def resumePage(slug: String): ZIO[Any, String, Boolean] =
    val saved = AssetStore.savedPageKey(slug)
    for
      present <- exists(saved)
      _       <- ZIO.when(present)(copyObject(saved, AssetStore.indexKey(slug)))
      _       <- deleteIfPresent(saved)
    yield present

  /** Invalidates everything under `/<slug>` and polls until CloudFront reports `Completed`, which
    * takes seconds to minutes. Fails after 5 minutes. A pause needs the wait: until the edge drops
    * the portal's cached `index.html`, a guest still gets the SPA and an API that now refuses.
    */
  def invalidateAndWait(slug: String): ZIO[Any, String, String] = invalidatePrefix(slug).flatMap:
    id =>
      val poll = ZIO
        .attemptBlocking(
          cdn
            .getInvalidation(
              GetInvalidationRequest.builder().distributionId(distributionId).id(id).build())
            .invalidation()
            .status())
        .mapError(e => s"Could not read invalidation $id: ${e.getMessage}")
      poll
        .repeatUntil(_ == "Completed")
        .timeoutFail(
          s"Invalidation $id was still running after 5 minutes. The edge may still be serving this location's portal, so nothing has been told it is paused yet; check the distribution.")(
          5.minutes)
        .as(id)

  /** Every object under a prefix, removed. Returns how many went.
    *
    * The caller must pass a trailing slash: `cafe` also matches `cafe-centro`, so `cafe/` is the
    * only prefix that stays inside one location.
    *
    * This takes the bundle copy, the operator's stylesheet and icon, and every uploaded video file
    * at `<slug>/videos/<id>`. The database rows referencing them survive, so afterwards a location
    * still lists videos as active whose files are gone.
    */
  def deletePrefix(prefix: String): ZIO[Any, String, Int] = listPrefix(prefix).flatMap: keys =>
    ZIO.foreachDiscard(keys)(deleteIfPresent).as(keys.size)

  /** Deletes a key, treating absence as success. */
  private def deleteIfPresent(key: String): ZIO[Any, String, Unit] = ZIO
    .attemptBlocking:
      s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build())
      ()
    .mapError(e => s"Could not delete $key: ${e.getMessage}")

  /** The object's ETag, or `None` when there is no such key.
    *
    * Usable as "is this still the copy we made" only for objects written in a single request, where
    * the ETag is the MD5 of the bytes. [[put]] sends one `PutObject` and [[copyObject]] one
    * `CopyObject`; a multipart upload would not have that property.
    */
  private def eTagOf(key: String): ZIO[Any, String, Option[String]] = ZIO
    .attemptBlocking:
      Option(s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()).eTag())
    .catchSome { case _: NoSuchKeyException =>
      ZIO.none
    }
    .mapError(e => s"Could not read the ETag of $key: ${e.getMessage}")

  /** Every key under a prefix, following the `ListObjectsV2` continuation token to the end. */
  private[mcp] def listPrefix(prefix: String): ZIO[Any, String, List[String]] =
    def page(token: Option[String]): ZIO[Any, Throwable, (List[String], Option[String])] = ZIO
      .attemptBlocking:
        val request = ListObjectsV2Request.builder().bucket(bucket).prefix(prefix)
        val response = s3.listObjectsV2(token.fold(request)(request.continuationToken).build())
        val keys = response.contents().asScala.map(_.key()).toList
        val next =
          if response.isTruncated then
            Option(response.nextContinuationToken())
          else
            None
        (keys, next)
    def loop(token: Option[String], acc: List[String]): ZIO[Any, Throwable, List[String]] = page(
      token).flatMap: (keys, next) =>
      next match
        case Some(continuation) =>
          loop(Some(continuation), acc ++ keys)
        case None =>
          ZIO.succeed(acc ++ keys)
    loop(None, Nil).mapError(e => s"Could not list '$prefix' in $bucket: ${e.getMessage}")
  end listPrefix

  private def copyObject(source: String, destination: String): ZIO[Any, String, Unit] = ZIO
    .attemptBlocking:
      s3.copyObject(
        CopyObjectRequest
          .builder()
          .sourceBucket(bucket)
          .sourceKey(source)
          .destinationBucket(bucket)
          .destinationKey(destination)
          .metadataDirective(MetadataDirective.COPY)
          .build())
      ()
    .mapError(e => s"Could not copy $source to $destination: ${e.getMessage}")

  private def invalidateNow(slug: String, asset: Asset): ZIO[Any, String, String] = ZIO
    .attemptBlocking:
      // A caller reference must be unique per request.
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

  /** What the SPA fetches. Video rows store this rather than the key, since the player needs a URL.
    */
  def publicUrl(key: String): String = s"${publicBaseUrl.stripSuffix("/")}/$key"

  /** Where an MCP App's script is served from, under a name that never changes: `ui/<name>` on the
    * same base the portal's assets use. `mill client.publish` puts the file there, and the hashed
    * chunks the entry imports are resolved by the browser against this URL, so they are published
    * flat beside it.
    */
  def scriptUrl(name: String): String = publicUrl(s"ui/$name")

  /** A local ES import, in both forms the bundler emits: `from"./x"` for a binding and a bare
    * `import"./x"` for a side effect. Deliberately not the dynamic `import("./x")`, so a lazily
    * loaded chunk is not preloaded.
    */
  private val localImport = """(?:from|import)\s*"\./([^"]+)"""".r

  /** The chunks the published entry imports, as URLs for `<link rel="modulepreload">`.
    *
    * Read from the deployed object rather than from a compiled-in list: the chunk names carry a
    * content hash, and a preload naming a hash nothing imports any more fetches a chunk the browser
    * will never use.
    *
    * Empty on any failure, since the page is correct without the hint and the first deploy of an
    * environment reaches this before anything has been published.
    */
  def scriptPreloads(entry: String): UIO[Seq[String]] = ZIO
    .attemptBlocking:
      val text =
        new String(
          s3.getObject(GetObjectRequest.builder().bucket(bucket).key(s"ui/$entry").build())
            .readAllBytes(),
          "UTF-8")
      localImport.findAllMatchIn(text).map(found => scriptUrl(found.group(1))).toSeq.distinct
    .orElseSucceed(Seq.empty)

  /** Fetches `ui/upload.js` at startup and returns a warning when it would not load as a module.
    *
    * A module script is fetched in CORS mode, so the CDN has to answer
    * `Access-Control-Allow-Origin` or the browser blocks it and the page renders inert, with
    * nothing in the console, the tool call or this server's logs.
    *
    * A warning and not a failure: the first deploy of a new environment reaches this before
    * `client.publish` has ever run. Nothing here configures CORS; see docs/mcp-iam.adoc.
    */
  def verifyScriptDelivery: ZIO[Client, Nothing, Option[String]] =
    val target = scriptUrl("upload.js")
    val probe =
      for
        url <- ZIO.fromEither(URL.decode(target)).mapError(e => new RuntimeException(e.getMessage))
        // An `Origin` header is required: without one the response carries no CORS headers whether
        // or not any are configured. Any value distinguishes a configured `*` from silence.
        request = Request.get(url).addHeader("Origin", "https://probe.invalid")
        response <- ZIO.serviceWithZIO[Client](_.batched(request))
        // Drained rather than ignored, so the client does not hold the connection.
        _ <- response.body.asString
      yield (response.status.code, response.rawHeader("access-control-allow-origin"))
    probe
      // Bounded: this is in the startup path, so a CDN that hangs must not stop the server from
      // serving.
      .timeoutFail(new RuntimeException("timed out after 5s"))(5.seconds)
      .fold(
        e => Some(s"could not be reached ($target): ${e.getMessage}"),
        (status, allowOrigin) =>
          if status == 404 then
            Some(s"is not published yet ($target). Run `mill client.publish`.")
          else if status != 200 then
            Some(s"answered HTTP $status ($target).")
          else if allowOrigin.isEmpty then
            Some(
              s"is served but without Access-Control-Allow-Origin ($target), so a browser will block it as a module and the page will render inert. See docs/mcp-iam.adoc.")
          else
            None
      )
  end verifyScriptDelivery

  /** The origin those scripts come from, for `_meta.ui.csp.resourceDomains`, which maps to
    * `script-src`. Without it the module is blocked and the page renders inert.
    */
  def scriptOrigin: String =
    val parsed = URI.create(publicBaseUrl)
    val port =
      if parsed.getPort < 0 then
        ""
      else
        s":${parsed.getPort}"
    s"${parsed.getScheme}://${parsed.getHost}$port"

  /** The key behind a stored URL. The inverse of [[publicUrl]].
    *
    * Two readings, in this order. A URL this configuration produced is undone exactly by removing
    * the base it was built from. Anything else falls back to the URI path, so a row written when
    * the portal lived on another domain still resolves; that fallback drops a leading bucket
    * segment, which path-style addressing against a local endpoint puts there.
    *
    * The exact base has to be tried first, or a location whose slug matches the bucket name loses
    * its first segment.
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

  /** A URL the browser can PUT the video to directly, valid for `validFor`. The bytes never pass
    * through this server.
    *
    * Signed without binding a Content-Type, so `SignedHeaders` is `host` alone and the browser's
    * PUT decides what S3 records. `confirm_video_upload` checks what arrived.
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

  /** Whether the object is actually there. The video row is written before the upload and starts
    * inactive, so this is what separates a finished upload from an abandoned one.
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
  /** A day, matching the bundle. An operator's edit invalidates CloudFront, so this bounds only the
    * browser's own cache.
    */
  private[mcp] val CacheControl = "public, max-age=86400"

  /** The page the edge serves for a location, and for every SPA route beneath it. */
  def indexKey(slug: String): String = s"$slug/index.html"

  /** Where the maintenance page would sit if it were copied per location, which it deliberately is
    * not: the edge would serve it by name to anyone while the portal is healthy. Named only so
    * [[AssetStore.copyBundle]] can leave it out.
    */
  def maintenanceKey(slug: String): String = s"$slug/maintenance.html"

  /** The one copy of the maintenance page: shared, like the bundle it comes from. */
  val BundleMaintenanceKey: String = "bundle/maintenance.html"

  /** Where a pause keeps the page it replaced. Outside the location's own prefix, since anything
    * under `<slug>/` is served by the edge and would keep the real portal reachable by name for the
    * whole of a pause.
    *
    * Closing it properly needs a deny on the `_paused` prefix at the distribution, plus a matching
    * one on `bundle`; both belong to the infra repository, and until they exist this is obscurity
    * rather than a closed door.
    */
  def savedPageKey(slug: String): String = s"_paused/$slug/site.html"

  /** Requires `ASSETS_BUCKET`, `CLOUDFRONT_DISTRIBUTION_ID` and `PUBLIC_BASE_URL`, and fails
    * startup without them rather than on the first write.
    */
  val layer: ZLayer[Any, Throwable, AssetStore] = ZLayer.scoped:
    for
      bucket        <- required("ASSETS_BUCKET")
      distribution  <- required("CLOUDFRONT_DISTRIBUTION_ID")
      publicBaseUrl <- required("PUBLIC_BASE_URL")
      endpoint      <- System.env("AWS_ENDPOINT_URL").orDie
      s3  <- ZIO.acquireRelease(ZIO.attempt(s3ClientFor(endpoint)))(c => ZIO.succeed(c.close()))
      cdn <- ZIO.acquireRelease(ZIO.attempt(cdnClientFor(endpoint)))(c => ZIO.succeed(c.close()))
      presigner <-
        ZIO.acquireRelease(ZIO.attempt(presignerFor(endpoint)))(p => ZIO.succeed(p.close()))
    yield AssetStore(bucket, distribution, publicBaseUrl, s3, cdn, presigner)

  /** `AWS_ENDPOINT_URL` points the S3 client somewhere other than AWS — LocalStack, for testing the
    * upload flow without an account.
    *
    * Path-style addressing comes with it: the default puts the bucket in the hostname, and
    * `bucket.localhost` resolves to nothing. Credentials are still required by the signer but never
    * checked.
    */
  private[mcp] def s3ClientFor(endpoint: Option[String]): S3Client =
    endpoint match
      case Some(url) =>
        S3Client.builder().endpointOverride(URI.create(url)).forcePathStyle(true).build()
      case None =>
        S3Client.create()

  /** The same override, set explicitly rather than left to the SDK's own `AWS_ENDPOINT_URL`
    * handling, so every client here reads alike.
    */
  private[mcp] def cdnClientFor(endpoint: Option[String]): CloudFrontClient =
    endpoint match
      case Some(url) =>
        CloudFrontClient.builder().endpointOverride(URI.create(url)).build()
      case None =>
        CloudFrontClient.create()

  private[mcp] def presignerFor(endpoint: Option[String]): S3Presigner =
    endpoint match
      case Some(url) =>
        S3Presigner
          .builder()
          .endpointOverride(URI.create(url))
          // Without this the signed URL names a host the browser cannot resolve, and the failure
          // shows up as a network error at upload time.
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
