-- Whether a location's portal calls its UniFi controller at all. With 0 the access handler skips
-- authorizing and revoking guests, whatever else is configured. DEFAULT 1 keeps every existing
-- location as it was.

ALTER TABLE locations ADD COLUMN unifi_is_enabled INTEGER NOT NULL DEFAULT 1;
