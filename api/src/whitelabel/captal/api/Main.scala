package whitelabel.captal.api

import com.typesafe.config.ConfigFactory
import io.getquill.SnakeCase
import io.getquill.jdbczio.Quill
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import whitelabel.captal.core.infrastructure.{SessionRepository, SurveyRepository}
import whitelabel.captal.infra.{SessionRepositoryQuill, SurveyRepositoryQuill}
import zio.*
import zio.http.Server

object Main extends ZIOAppDefault:
  private val endpoints = SurveyRoutes.routes

  private val routes = ZioHttpInterpreter().toHttp(endpoints)

  private val serverConfigLayer: ZLayer[Any, Throwable, Server.Config] = ZLayer.fromZIO:
    ZIO.attempt:
      val config = ConfigFactory.load()
      val host = config.getString("server.host")
      val port = config.getInt("server.port")
      Server.Config.default.binding(host, port)

  private val quillLayer = Quill.Sqlite.fromNamingStrategy(SnakeCase)

  private val dataSourceLayer = Quill.DataSource.fromPrefix("database")

  private val sessionRepoLayer = SessionRepositoryQuill.layer[io.getquill.SqliteDialect, SnakeCase]

  private val surveyRepoLayer = SurveyRepositoryQuill.layer[io.getquill.SqliteDialect, SnakeCase]

  private val repoLayers: ZLayer[Any, Throwable, SessionRepository[Task] & SurveyRepository[Task]] =
    dataSourceLayer >>> quillLayer >>> (sessionRepoLayer ++ surveyRepoLayer)

  private def logRoutes(host: String, port: Int): Task[Unit] =
    val routeInfos = endpoints.map: e =>
      val method = e.endpoint.method.map(_.method).getOrElse("*")
      val path = e.endpoint.showPathTemplate()
      s"  $method $path"
    ZIO.logInfo(s"Server starting on $host:$port") *>
      ZIO.logInfo(s"Mounted routes:\n${routeInfos.mkString("\n")}")

  override val run: ZIO[Any, Throwable, Nothing] =
    for
      config <- ZIO.attempt:
        val c = ConfigFactory.load()
        (c.getString("server.host"), c.getInt("server.port"))
      (host, port) = config
      _      <- logRoutes(host, port)
      result <- Server
        .serve(routes)
        .provide(
          ZLayer.succeed(Server.Config.default.binding(host, port)),
          Server.live,
          repoLayers)
    yield result
end Main
