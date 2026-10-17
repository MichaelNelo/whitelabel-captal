package whitelabel.captal.mcp

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

/** One asset a location can override, and how it has to be stored.
  *
  * The content type and encoding are not incidental: the SPA fetches these by fixed relative name,
  * so a stylesheet served without `Content-Encoding: gzip` arrives as bytes the browser will not
  * read, and an icon served as anything but `image/svg+xml` does not render.
  */
enum Asset(val fileName: String, val contentType: String, val gzipped: Boolean):
  case Styles    extends Asset("custom-styles.css.gz", "text/css", true)
  case BrandIcon extends Asset("brand-icon.svg", "image/svg+xml", false)

/** What a bundle copy did, reported because both halves are invisible from the outside.
  *
  * @param objects
  *   how many were written. Zero means the bundle prefix is empty — the SPA has never been
  *   published — which is a different problem from a deploy that failed.
  * @param iconPreserved
  *   whether the location's own `brand-icon.svg` was left alone. False is the ordinary case for a
  *   location with no branding of its own, which then gets the bundle's default.
  */
final case class BundleCopy(objects: Int, iconPreserved: Boolean)

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
    // False against a local endpoint: every invalidation becomes `"skipped-no-cdn"` instead of a
    // CloudFront call. Set from `endpoint.isEmpty` in the layer, and defaulted to true so that
    // forgetting to pass it can only ever make the server stricter.
    //
    // See *Decisiones de Diseño* for why a local run skips invalidation rather than attempting it.
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

  /** Everything under a location's prefix at once, for the one caller that has just rewritten all
    * of it. [[invalidate]] names a single key because that is all an asset write touches; a bundle
    * copy replaces every file the SPA loads, and enumerating them would be a longer path list
    * saying the same thing.
    */
  def invalidatePrefix(slug: String): ZIO[Any, String, String] =
    if !cdnEnabled then
      ZIO.succeed("skipped-no-cdn")
    else
      ZIO
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

  /** The shared bundle, copied under one location's own prefix.
    *
    * This is what `captal locations push` did first, and nothing did once the CLI was deleted —
    * which left a freshly deployed slug answering its API and serving no SPA at all. The portal is
    * fetched from `<slug>/`, so until these objects exist a guest gets a 404 for the page itself.
    *
    * Server-side copies, which is not a detail: `MetadataDirective.COPY` carries `Content-Type` and
    * `Content-Encoding` across, and the bundle's `.gz` files are unreadable without the latter —
    * the browser gets gzip bytes labelled as text. It also means no object travels through this
    * process.
    *
    * Idempotent, and safe to re-run after a bundle publish: a copy overwrites what was there.
    *
    * The location's own icon is the exception, and the reason this is not a plain recursive copy.
    * `brand-icon.svg` is *in* the bundle as a default and `set_location_icon` writes to the same
    * key, so copying it blindly would replace a location's branding with the stock icon on every
    * deploy.
    *
    * What decides is an ETag comparison rather than whether the key exists. Existence cannot tell
    * an operator's icon from the default this copy itself wrote on the previous deploy, and reading
    * it that way costs two things: every redeploy would report `iconPreserved` for a location that
    * has no icon of its own, and no location would ever pick up a change to the default. An
    * untouched copy still matches its source and is refreshed; anything the operator wrote differs
    * and is left alone.
    */
  def copyBundle(slug: String): ZIO[Any, String, BundleCopy] =
    val prefix = "bundle/"
    val iconKey = key(slug, Asset.BrandIcon)
    val bundleIconKey = s"$prefix${Asset.BrandIcon.fileName}"
    for
      bundleIcon <- eTagOf(bundleIconKey)
      ownIcon    <- eTagOf(iconKey)
      // Custom only when both exist and differ. A location with no icon has nothing to preserve,
      // and a bundle with no default has nothing to overwrite it with.
      iconIsCustom = ownIcon.isDefined && bundleIcon.isDefined && ownIcon != bundleIcon
      sources <- listPrefix(prefix)
      copied  <-
        ZIO.foreach(sources): source =>
          val destination = s"$slug/${source.stripPrefix(prefix)}"
          // Skipped rather than copied and then restored: restoring leaves a window in which the
          // portal serves the wrong icon, and skipping has none.
          if destination == iconKey && iconIsCustom then
            ZIO.succeed(None)
          // The maintenance page is deliberately not copied here. A pause installs it *as*
          // `index.html` and sources it from the bundle, so a per-location copy would be a public
          // URL at every slug that nothing ever asks for — and one that serves a maintenance page
          // to a guest while the portal is perfectly healthy.
          else if destination == AssetStore.maintenanceKey(slug) then
            ZIO.succeed(None)
          else
            copyObject(source, destination).as(Some(destination))
      // Deploying a paused location brings it back, which is already true of the rule and the
      // service: `ensureRule` forwards and `upsertService` asks for one task. The saved page would
      // otherwise linger and a later resume would restore something older than what is now live.
      _ <- deleteIfPresent(AssetStore.savedPageKey(slug))
    yield BundleCopy(copied.flatten.size, iconPreserved = iconIsCustom)
    end for
  end copyBundle

  /** Puts the maintenance page where the portal's own page was, and keeps the original.
    *
    * The edge serves `<slug>/index.html` for the location and for every SPA route under it, so
    * replacing that one object is the whole of making the edge serve maintenance — no CloudFront
    * function, no KeyValueStore, nothing read per request. The state is which object is at that
    * key, the same way a paused API is which action is on the listener rule.
    *
    * The maintenance page comes from the bundle rather than from a per-location copy, and the
    * original goes to a prefix that is not the location's. Both for the same reason: a page that
    * exists under `<slug>/` is a page the edge will serve to anyone who asks for it by name, and
    * neither of these should ever be served except as `index.html` and except while paused.
    *
    * Both HTML files reference `./main.js`, `./maintenance.js` and `./styles.css` relatively, and
    * all three already sit under `<slug>/`, so the swapped page resolves its own assets with
    * nothing else moved.
    */
  def pausePage(slug: String): ZIO[Any, String, Unit] =
    val live = AssetStore.indexKey(slug)
    for
      // Saved first. If the copy of the maintenance page failed after the original was overwritten,
      // the location would have no page at all and nothing to restore it from.
      present <- exists(live)
      _       <- ZIO.when(present)(copyObject(live, AssetStore.savedPageKey(slug)))
      _       <- copyObject(AssetStore.BundleMaintenanceKey, live)
    yield ()

  /** Puts the location's own page back, from the copy [[pausePage]] kept.
    *
    * Restores what was live rather than re-copying from the bundle, so a pause and a resume are
    * exactly reversible: a bundle published in between does not arrive as a side effect of ending a
    * pause. `deploy_location` is how a location takes a newer bundle, and it says so.
    */
  def resumePage(slug: String): ZIO[Any, String, Boolean] =
    val saved = AssetStore.savedPageKey(slug)
    for
      present <- exists(saved)
      _       <- ZIO.when(present)(copyObject(saved, AssetStore.indexKey(slug)))
      _       <- deleteIfPresent(saved)
    yield present

  /** An invalidation, waited out.
    *
    * Dispatching one and returning is not enough for a pause: the point of swapping the page before
    * the API stops is that a guest told "paused" can be sent to a page that is already being
    * served. While the edge still has the portal's `index.html` cached, that guarantee is nominal —
    * the guest gets the SPA, calls an API that is now refusing, and the redirect lands back on a
    * cached copy of the thing it just left.
    *
    * So this polls until CloudFront says `Completed`. That is seconds to minutes, which is what
    * makes the operation that calls it a task rather than a call.
    */
  def invalidateAndWait(slug: String): ZIO[Any, String, String] =
    if !cdnEnabled then
      ZIO.succeed("skipped-no-cdn")
    else
      invalidatePrefix(slug).flatMap: id =>
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

  /** Everything a location has in the bucket, removed. Returns how many objects went.
    *
    * The trailing slash is load-bearing and is the `familyPrefix` bug one more time: a prefix of
    * `cafe` also matches `cafe-centro`, and a teardown that took a neighbour's assets with it would
    * be found by its guests rather than by us. `cafe/` matches only its own.
    *
    * What goes with it is more than the bundle copy: the operator's stylesheet and icon, and every
    * uploaded video file, which live at `<slug>/videos/<id>`. The rows that reference them are in
    * the database and survive, so after this a location still lists videos as active whose files
    * are gone. That is why [[DeployTools]] says so in its next steps rather than leaving it to be
    * discovered by a guest watching a player that will not load.
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
    * For an object written in a single request this is the MD5 of its bytes, which is what makes it
    * usable as "is this still the copy we made". Multipart uploads do not have that property, and
    * nothing writes these two assets that way: [[put]] sends one `PutObject` and [[copyObject]] one
    * `CopyObject`.
    */
  private def eTagOf(key: String): ZIO[Any, String, Option[String]] = ZIO
    .attemptBlocking:
      Option(s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()).eTag())
    .catchSome { case _: NoSuchKeyException =>
      ZIO.none
    }
    .mapError(e => s"Could not read the ETag of $key: ${e.getMessage}")

  /** Every key under a prefix, following the continuation token.
    *
    * Paged because a truncated listing is the kind of bug that appears only once the bundle passes
    * a thousand objects, and then appears as a portal quietly missing a few hashed assets.
    */
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

  /** Where an MCP App's script is served from, under a name that never changes.
    *
    * Built from the same base the portal's assets use, because the page has to name its script
    * without knowing anything about its content — that is what keeps the script out of the server's
    * resources, the same way the content itself stopped travelling in the deploy artifact.
    * `client.publish` is what puts the file there. The hashed chunks the entry imports are resolved
    * by the browser against this URL, which is why they are published flat beside it.
    */
  def scriptUrl(name: String): String = publicUrl(s"ui/$name")

  /** A local ES import, in both forms the bundler emits: `from"./x"` for a binding and a bare
    * `import"./x"` for a side effect. Deliberately not `import("./x")`, the dynamic form — a
    * parenthesis is not a quote, which is what keeps a lazily loaded chunk from being preloaded.
    */
  private val localImport = """(?:from|import)\s*"\./([^"]+)"""".r

  /** The chunks the published entry imports, as URLs for `<link rel="modulepreload">`.
    *
    * Read from the object that is actually deployed rather than from a list this server carries,
    * and that choice is the whole design. The chunk names carry a content hash, so a list compiled
    * in goes stale the next time the bundle is published — and a preload naming a hash nothing
    * imports any more is *worse* than no preload, because the browser fetches a chunk it will never
    * use. The published entry is the only thing that cannot be out of step with itself.
    *
    * What the hint buys, measured in a throttled browser: without it the page waits for the HTML,
    * parses it to find the entry, waits for the entry, parses *that* to find the chunks, and waits
    * again — three sequential waits. Naming them here makes it two, because the chunks go out
    * together with the entry. That was 107 ms at 100 ms of round-trip time and 308 ms at 300 ms.
    *
    * Failing to silence is deliberate. The page is correct without the hint, the first deploy of an
    * environment reaches this before anything has ever been published, and an optimisation that can
    * fail a render is a worse trade than one that can be absent.
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

  /** Checks the one thing about the MCP App pages that fails without a word.
    *
    * A module script is fetched in CORS mode, so the CDN has to answer
    * `Access-Control-Allow-Origin` or the browser blocks it — and the page then renders, lays out,
    * and does nothing. No console entry the operator would see, no failed tool call, nothing in
    * this server's logs. The same shape of failure as the upload PUT being refused at the
    * preflight, except that one at least happens while they are looking at the screen.
    *
    * So it is asked at startup, by fetching what the page will fetch. A warning and not a failure:
    * the first deploy of a new environment reaches this before `client.publish` has ever run, and
    * refusing to start would make the server depend on an artifact it does not own. The point is
    * that the answer is in the log before an operator finds it by opening a dead page.
    *
    * Neither this nor anything else here configures it; see docs/MCP_IAM.md.
    */
  def verifyScriptDelivery: ZIO[Client, Nothing, Option[String]] =
    val target = scriptUrl("upload.js")
    val probe =
      for
        url <- ZIO.fromEither(URL.decode(target)).mapError(e => new RuntimeException(e.getMessage))
        // Without an `Origin` the response carries no CORS headers whether or not any are
        // configured, so asking without one proves nothing. A browser would send the sandbox
        // origin; any value distinguishes a configured `*` from silence.
        request = Request.get(url).addHeader("Origin", "https://probe.invalid")
        response <- ZIO.serviceWithZIO[Client](_.batched(request))
        // Drained rather than ignored. `batched` has already read the body, and leaving the
        // response unconsumed is how a client ends up holding connections at startup.
        _ <- response.body.asString
      yield (response.status.code, response.rawHeader("access-control-allow-origin"))
    probe
      // Bounded, because this is in the startup path: a CDN that hangs must not stop the server
      // from serving, and the timeout is itself an answer worth logging.
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
              s"is served but without Access-Control-Allow-Origin ($target), so a browser will block it as a module and the page will render inert. See docs/MCP_IAM.md.")
          else
            None
      )
  end verifyScriptDelivery

  /** The origin those scripts come from, for `_meta.ui.csp.resourceDomains` — which maps to
    * `script-src` among the static-resource directives. Without it the module is blocked and the
    * page renders inert, with nothing the operator can see.
    */
  def scriptOrigin: String =
    val parsed = URI.create(publicBaseUrl)
    val port =
      if parsed.getPort < 0 then
        ""
      else
        s":${parsed.getPort}"
    s"${parsed.getScheme}://${parsed.getHost}$port"

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

  /** The page the edge serves for a location, and for every SPA route beneath it. */
  def indexKey(slug: String): String = s"$slug/index.html"

  /** Where the maintenance page would sit if it were copied per location — which it deliberately is
    * not. Named so [[AssetStore.copyBundle]] can leave it out and so the reason has somewhere to
    * live: a maintenance page reachable at `<slug>/maintenance.html` is a page the edge will serve
    * to anyone who asks for it while the portal is perfectly healthy.
    */
  def maintenanceKey(slug: String): String = s"$slug/maintenance.html"

  /** The one copy of the maintenance page: shared, like the bundle it comes from. */
  val BundleMaintenanceKey: String = "bundle/maintenance.html"

  /** Where a pause keeps the page it replaced.
    *
    * Deliberately not `<slug>/site.html`, which is what it was going to be: anything under a
    * location's own prefix is served by the edge, so the real portal would stay reachable by name
    * for the whole of a pause — which is the one thing a pause is for. Outside that prefix it is at
    * least not guessable from the slug, and a single deny on the `_paused` prefix at the
    * distribution closes it properly. That rule, and a matching one on `bundle`, belong to the
    * infra repository; until they exist this is obscurity rather than a closed door, and the honest
    * summary is in the `deploy-location` skill.
    */
  def savedPageKey(slug: String): String = s"_paused/$slug/site.html"

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
