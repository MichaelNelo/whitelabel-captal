-- Whether a location's portal requires the link shortener's fingerprint on the redirect. With 0 a
-- guest whose redirect carries none is given a fixed stand-in instead of being turned away. DEFAULT 1
-- keeps every existing location as it was.

ALTER TABLE locations ADD COLUMN fingerprint_is_required INTEGER NOT NULL DEFAULT 1;
