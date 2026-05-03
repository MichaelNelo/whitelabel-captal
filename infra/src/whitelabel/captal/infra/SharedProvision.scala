package whitelabel.captal.infra

import com.typesafe.config.ConfigFactory
import io.getquill.jdbczio.Quill
import whitelabel.captal.infra.provision.ProvisionService
import whitelabel.captal.infra.schema.QuillSqlite
import zio.*

/** Standalone entry point for shared provisioning (surveys + advertisers). Used by
  * `captal shared push` via an ephemeral ECS task.
  *
  * Required env vars: SHARED_DIR, DB_URL
  */
object SharedProvision extends ZIOAppDefault:

  override val run: ZIO[Any, Throwable, Unit] = ZIO
    .serviceWithZIO[QuillSqlite]: quill =>
      for
        sharedDir <- ZIO.attempt:
          val dir = ConfigFactory.load().getString("shared.dir")
          if dir.isEmpty then
            throw new RuntimeException("SHARED_DIR is required")
          dir
        _ <- ProvisionService.runShared(quill, sharedDir)
      yield ()
    .provide(RqliteDataSource.layer, Quill.Sqlite.fromNamingStrategy(io.getquill.SnakeCase))
