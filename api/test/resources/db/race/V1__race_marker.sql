-- The migration `MigrationLockSuite` races migrators against. Not part of any schema.
--
-- Its own location and its own history table, so racing it cannot touch `db/migration` or the
-- database the other suites share.
--
-- The shape is the point. `CREATE TABLE IF NOT EXISTS` succeeds for everyone, which is what makes
-- this a test of the lock rather than of SQLite: a migration whose DDL collides with itself (a bare
-- `CREATE TABLE`) fails the second migrator's commit on its own and would pass with the lock
-- removed. The `INSERT` has no `ON CONFLICT`, so a second application appends rather than failing,
-- and the row count is a direct count of how many migrators applied it.
--
-- That is V22's shape, which is why it was chosen: `V22__english_video_types.sql` rebuilds
-- `advertiser_videos` through `INSERT INTO advertiser_videos_new`, so applying it twice duplicates
-- every video it copies.

CREATE TABLE IF NOT EXISTS migration_race_marker (note TEXT NOT NULL);

INSERT INTO migration_race_marker (note) VALUES ('applied');
