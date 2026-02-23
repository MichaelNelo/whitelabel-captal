package whitelabel.captal.api

import com.typesafe.config.ConfigFactory
import io.getquill.SnakeCase
import io.getquill.jdbczio.Quill
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import whitelabel.captal.core.application.{Event, EventHandler}
import whitelabel.captal.core.infrastructure.{SessionRepository, SurveyRepository}
import whitelabel.captal.infra.eventhandlers.AnswerPersistenceHandler
import whitelabel.captal.infra.{SessionRepositoryQuill, SurveyRepositoryQuill, TransactionalEventHandler}
import zio.*
import zio.http.Server

object Main extends ZIOAppDefault:
  private val endpoints = SurveyRoutes.routes

  private val routes = ZioHttpInterpreter().toHttp(endpoints)

  private val quillLayer = Quill.Sqlite.fromNamingStrategy(SnakeCase)

  private val dataSourceLayer = Quill.DataSource.fromPrefix("database")

  private val sessionRepoLayer = SessionRepositoryQuill.layer[io.getquill.SqliteDialect, SnakeCase]

  private val surveyRepoLayer = SurveyRepositoryQuill.layer[io.getquill.SqliteDialect, SnakeCase]

  private val eventHandlerLayer
      : ZLayer[Quill[io.getquill.SqliteDialect, SnakeCase], Nothing, EventHandler[Task, Event]] =
    ZLayer.fromFunction: (quill: Quill[io.getquill.SqliteDialect, SnakeCase]) =>
      val dbHandler = AnswerPersistenceHandler[io.getquill.SqliteDialect, SnakeCase]
      TransactionalEventHandler(dbHandler, quill)

  private val appLayers: ZLayer[
    Any,
    Throwable,
    SessionRepository[Task] & SurveyRepository[Task] & EventHandler[Task, Event]] =
    dataSourceLayer >>> quillLayer >>> (sessionRepoLayer ++ surveyRepoLayer ++ eventHandlerLayer)

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
          appLayers)
    yield result
end Main
