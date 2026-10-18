-- Migrate to UniFi Integration v1 API.
-- The new API path /proxy/network/integration/v1/sites/{siteId}/clients/... uses a UUID
-- for siteId (not the legacy site name like "default"), and the unifiOs flag is no longer
-- relevant because the integration v1 path is uniform across UCG firmware versions.
--
-- Rename unifi_site → unifi_site_id (semantic shift from name to UUID) and drop unifi_use_os.
-- rqlite v9 ships SQLite >= 3.35 so RENAME COLUMN and DROP COLUMN are both supported.

ALTER TABLE locations RENAME COLUMN unifi_site TO unifi_site_id;
ALTER TABLE locations DROP COLUMN unifi_use_os;
