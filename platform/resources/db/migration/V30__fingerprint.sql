-- The link shortener's device fingerprint reaches the portal as `fingerprint`, and the columns that
-- keep it take the same name.

ALTER TABLE sessions RENAME COLUMN click_id TO fingerprint;

ALTER TABLE devices RENAME COLUMN click_id TO fingerprint;

DROP INDEX idx_devices_click_id;

CREATE INDEX idx_devices_fingerprint ON devices(fingerprint);
