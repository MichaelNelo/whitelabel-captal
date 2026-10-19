package whitelabel.captal.api

import com.typesafe.config.ConfigFactory
import zio.*
import zio.http.Server

/** Everything this process reads from its environment before it can serve anything.
  *
  * @param provisionDir
  *   read and ignored, along with `sharedDir`; content comes from the database.
  */
final private[api] case class ServerSettings(
    config: Server.Config,
    devMode: Boolean,
    devEndpoints: Boolean,
    locationSlug: Option[String],
    provisionDir: Option[String],
    sharedDir: Option[String],
    unifiProxyUrl: Option[String])

private[api] object ServerSettings:
  val layer: ZLayer[Any, Throwable, ServerSettings] = ZLayer.fromZIO:
    ZIO.attempt:
      val c = ConfigFactory.load()
      ServerSettings(
        config = Server.Config.default.binding(c.getString("server.host"), c.getInt("server.port")),
        devMode = c.getBoolean("server.dev-mode"),
        devEndpoints = c.getBoolean("server.dev-endpoints"),
        locationSlug = Option(c.getString("location.slug")).filter(_.nonEmpty),
        provisionDir = Option(c.getString("provision.dir")).filter(_.nonEmpty),
        sharedDir = Option(c.getString("shared.dir")).filter(_.nonEmpty),
        unifiProxyUrl = Option(c.getString("unifi.proxy-url")).filter(_.nonEmpty)
      )
