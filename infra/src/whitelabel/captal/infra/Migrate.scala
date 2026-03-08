package whitelabel.captal.infra

import com.typesafe.config.ConfigFactory
import fly4s.Fly4s
import fly4s.data.{Fly4sConfig, Location, MigrationVersion}
import zio.*
import zio.interop.catz.*

object Migrate extends ZIOAppDefault:

  private val fly4sConfig = Fly4sConfig.default.copy(
    locations = List(Location("db/migration")),
    group = false,
    mixed = true,
    baselineOnMigrate = true,
    baselineVersion = MigrationVersion("0"))

  override val run: ZIO[Any, Throwable, Unit] = ZIO
    .attempt(ConfigFactory.load().getString("database.jdbcUrl"))
    .flatMap: url =>
      Fly4s
        .makeFor[Task](ZIO.attempt(RqliteDataSource.create(url)), config = fly4sConfig)
        .use: fly4s =>
          for
            _      <- ZIO.logInfo("Running database migrations...")
            result <- fly4s.migrate
            _      <- ZIO.logInfo(s"Migrations complete: ${result.migrationsExecuted} executed")
          yield ()
