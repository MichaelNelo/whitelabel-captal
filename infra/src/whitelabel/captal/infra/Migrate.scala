package whitelabel.captal.infra

import com.typesafe.config.ConfigFactory
import fly4s.Fly4s
import fly4s.data.{Fly4sConfig, Location, MigrationVersion}
import zio.*
import zio.interop.catz.*

object Migrate extends ZIOAppDefault:

  private def fly4sConfig(devSeed: Boolean) =
    val locations =
      if devSeed then List(Location("db/migration"), Location("db/migration-dev"))
      else List(Location("db/migration"))
    Fly4sConfig.default.copy(
      locations = locations,
      group = false,
      mixed = true,
      baselineOnMigrate = true,
      baselineVersion = MigrationVersion("0"))

  override val run: ZIO[Any, Throwable, Unit] = ZIO
    .attempt:
      val config = ConfigFactory.load()
      val url = config.getString("database.jdbcUrl")
      val devSeed = config.getBoolean("database.dev-seed")
      (url, devSeed)
    .flatMap: (url, devSeed) =>
      Fly4s
        .makeFor[Task](ZIO.attempt(RqliteDataSource.create(url)), config = fly4sConfig(devSeed))
        .use: fly4s =>
          for
            _ <- ZIO.logInfo(
              s"Running database migrations...${if devSeed then " (with dev seeds)" else ""}")
            result <- fly4s.migrate
            _      <- ZIO.logInfo(s"Migrations complete: ${result.migrationsExecuted} executed")
          yield ()
