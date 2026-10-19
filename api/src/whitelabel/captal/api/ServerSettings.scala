package whitelabel.captal.api

import com.typesafe.config.ConfigFactory
import zio.*
import zio.http.Server

/** Everything this process reads from its environment before it can serve anything.
  *
  * Its own file rather than a member of `Main`, because `AppLayers` needs it too and `Main`
  * depending on the pieces reads better than the pieces depending on the entry point.
  *
  * @param provisionDir
  *   read and ignored, along with `sharedDir`. Both named directories the API used to apply at
  *   startup; content comes from the database now. The task definitions that still set them should
  *   drop them — a variable that configures nothing is a promise that something still reads it.
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
