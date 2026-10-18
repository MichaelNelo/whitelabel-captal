-- Add click_id (UniFi captive-portal redirect parameter) to sessions.
-- Required for newly-created sessions. Legacy rows (created before this migration)
-- get the empty-string fallback; the API rejects new session creation when the
-- `X-Click-Id` header / `click_id` query param is missing.
ALTER TABLE sessions ADD COLUMN click_id TEXT NOT NULL DEFAULT '';
