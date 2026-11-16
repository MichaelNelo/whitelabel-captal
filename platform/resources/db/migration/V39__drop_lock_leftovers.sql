-- Drops what the database held of the locks before they moved to Redis.
--
-- `locations.current_run` (V38) was each location's operation lock: the run going on it, taken by a
-- conditional write. `migration_lock` was the migrations' lock, one row whose primary key was the
-- mutex, created on demand by the migrator rather than by a migration — absent, then, from a
-- database no migrator of an earlier release reached, hence `IF EXISTS`.
--
-- Both locks are in Redis since api/v1.4.0 and mcp/v1.6.0, which read and write neither. Every
-- location runs that release or a later one, so no image that used them can still start against
-- this database. `current_run` has no index, which `DROP COLUMN` would refuse.

ALTER TABLE locations DROP COLUMN current_run;

DROP TABLE IF EXISTS migration_lock;
