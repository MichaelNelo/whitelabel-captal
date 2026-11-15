-- Where a location stands, each state an operation that succeeded: `created` (never deployed),
-- `deployed`, `paused` or `destroyed`; and `state_run`, the backoffice run that left it so, none for
-- `created`. A deployed location has one access point at least: with none, every guest would be
-- turned away.
--
-- Every location starts `created`. The backoffice, which holds the runs, fills the state of those
-- that existed before from their last run that succeeded (`db/migration-mcp`, V3).

ALTER TABLE locations ADD COLUMN state TEXT NOT NULL DEFAULT 'created';
ALTER TABLE locations ADD COLUMN state_run TEXT;
