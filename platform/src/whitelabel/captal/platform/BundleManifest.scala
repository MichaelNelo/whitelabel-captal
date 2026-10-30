package whitelabel.captal.platform

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Instant

import io.circe.parser
import whitelabel.captal.core.recovery.Manifest
import zio.*

/** The bundle this location serves, as its `manifest.json` describes it: the version and, per file,
  * the length of each line. What a client error report is checked against.
  */
trait BundleManifest:
  /** `None` when it cannot be read. */
  def current: UIO[Option[Manifest]]

object BundleManifest:
  /** For a server configured with no manifest: every report is judged without one. */
  val absent: BundleManifest =
    new BundleManifest:
      def current: UIO[Option[Manifest]] = ZIO.none

  /** Read over HTTP from where the location's bundle is published, and kept for `ttl`: the bundle
    * changes with a deploy, not per request. A failed read is kept as long, so a missing manifest
    * costs one request a minute rather than one per error.
    */
  def fromUrl(url: String, ttl: Duration = 1.minute): UIO[BundleManifest] = Ref
    .make(Option.empty[(Instant, Option[Manifest])])
    .map: cache =>
      new BundleManifest:
        def current: UIO[Option[Manifest]] =
          for
            now    <- Clock.instant
            cached <- cache.get
            result <-
              cached match
                case Some((readAt, manifest)) if readAt.plus(ttl).isAfter(now) =>
                  ZIO.succeed(manifest)
                case _ =>
                  fetch(url).tap(manifest => cache.set(Some(now -> manifest)))
          yield result

  private def fetch(url: String): UIO[Option[Manifest]] = ZIO
    .attemptBlocking:
      val client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build()
      val request = HttpRequest
        .newBuilder(URI.create(url))
        .timeout(java.time.Duration.ofSeconds(3))
        .GET()
        .build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      Option.when(response.statusCode == 200)(response.body).flatMap(parse)
    .catchAll(e =>
      ZIO.logWarning(s"Could not read the bundle manifest at $url: ${e.getMessage}").as(None))

  /** `{"version": "1.4.0", "files": {"main.js": [120, 40000], ...}}`: each file's line lengths. */
  def parse(body: String): Option[Manifest] = parser
    .parse(body)
    .toOption
    .flatMap: json =>
      val cursor = json.hcursor
      for
        version <- cursor.get[String]("version").toOption
        files   <- cursor.get[Map[String, Vector[Int]]]("files").toOption
      yield Manifest(version, files)
end BundleManifest
