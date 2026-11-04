-- The terms of service a guest accepts before starting: one series of versions, the same for every
-- location. A change to the terms is a new version, a new row with a higher number; its texts are
-- rows of `terms_translations`, and adding or removing one of those is not a new version.

CREATE TABLE terms (
  id TEXT PRIMARY KEY,
  version INTEGER NOT NULL UNIQUE,
  created_at TEXT NOT NULL
);

CREATE TABLE terms_translations (
  terms_id TEXT NOT NULL REFERENCES terms(id),
  locale TEXT NOT NULL,
  body TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  PRIMARY KEY (terms_id, locale)
);

-- Which version a device accepted last, and when. NULL on a device that never accepted any.
ALTER TABLE devices ADD COLUMN terms_id TEXT REFERENCES terms(id);
ALTER TABLE devices ADD COLUMN terms_accepted_at TEXT;
