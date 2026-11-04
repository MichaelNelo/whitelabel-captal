-- A MAC is stored as the 48-bit integer its six bytes spell, most significant first, so two
-- spellings of one address are one value. NULL where there is none.
--
-- BIGINT rather than INTEGER: SQLite stores both as the same 64-bit integer, but the JDBC driver
-- reads a column declared INTEGER as a 32-bit int, and a MAC does not fit in one.
--
-- The text values are not carried over: no guest has reached a deployed portal yet, and a row
-- without a MAC matches no device, as the rows V27 left without one.

DROP INDEX idx_sessions_client_mac;
ALTER TABLE sessions DROP COLUMN client_mac;
ALTER TABLE sessions ADD COLUMN client_mac BIGINT;
CREATE INDEX idx_sessions_client_mac ON sessions(client_mac);

ALTER TABLE sessions DROP COLUMN ap_mac;
ALTER TABLE sessions ADD COLUMN ap_mac BIGINT;

DROP INDEX idx_devices_client_mac;
ALTER TABLE devices DROP COLUMN client_mac;
ALTER TABLE devices ADD COLUMN client_mac BIGINT;
CREATE INDEX idx_devices_client_mac ON devices(client_mac);
