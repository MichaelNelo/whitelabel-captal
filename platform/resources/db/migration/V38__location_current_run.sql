-- The location's lock: the backoffice run of a deploy, pause, resume or destroy going on it, none
-- while nothing is. Taken by a conditional write the leader serializes — `current_run` set to the new
-- run where it still holds what the starting command saw — and read back after the commit to learn
-- who won, since rqlite reports no row count inside a transaction. Released when the run completes
-- or fails; a run whose runner died keeps it until another run takes it over, finishing it or
-- destroying the location.

ALTER TABLE locations ADD COLUMN current_run TEXT;
