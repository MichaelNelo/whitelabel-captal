package whitelabel.captal.api

import com.typesafe.config.ConfigFactory
import whitelabel.captal.core.device.Policy
import zio.*
import zio.http.Server

/** Everything this process reads from its environment before it can serve anything.
  *
  * @param provisionDir
  *   read and ignored, along with `sharedDir`; content comes from the database.
  * @param deviceMatching
  *   which fingerprint fields make two sessions the same device. Read at startup, so a name that is
  *   not a policy stops the server rather than surfacing on the first guest.
  * @param bundleManifestUrl
  *   where the location's bundle publishes its `manifest.json`, which a client error report is
  *   checked against. Unset, reports are judged without it.
  */
final private[api] case class ServerSettings(
    config: Server.Config,
    devMode: Boolean,
    devEndpoints: Boolean,
    locationSlug: Option[String],
    provisionDir: Option[String],
    sharedDir: Option[String],
    unifiProxyUrl: Option[String],
    deviceMatching: Policy,
    bundleManifestUrl: Option[String])

private[api] object ServerSettings:
  val layer: ZLayer[Any, Throwable, ServerSettings] = ZLayer.fromZIO:
    for
      c        <- ZIO.attempt(ConfigFactory.load())
      matching <- ZIO
        .fromEither(Policy.fromConfig(Option(c.getString("device.matching"))))
        .mapError(new IllegalArgumentException(_))
      settings <- ZIO.attempt:
        ServerSettings(
          config = Server
            .Config
            .default
            .binding(c.getString("server.host"), c.getInt("server.port")),
          devMode = c.getBoolean("server.dev-mode"),
          devEndpoints = c.getBoolean("server.dev-endpoints"),
          locationSlug = Option(c.getString("location.slug")).filter(_.nonEmpty),
          provisionDir = Option(c.getString("provision.dir")).filter(_.nonEmpty),
          sharedDir = Option(c.getString("shared.dir")).filter(_.nonEmpty),
          unifiProxyUrl = Option(c.getString("unifi.proxy-url")).filter(_.nonEmpty),
          deviceMatching = matching,
          bundleManifestUrl = Option(c.getString("bundle.manifest-url")).filter(_.nonEmpty)
        )
    yield settings
end ServerSettings
