CREATE TABLE locations (
    id TEXT PRIMARY KEY,
    slug TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    is_active INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

ALTER TABLE advertiser_videos ADD COLUMN location_id TEXT REFERENCES locations(id);
ALTER TABLE surveys ADD COLUMN location_id TEXT REFERENCES locations(id);
ALTER TABLE sessions ADD COLUMN location_id TEXT REFERENCES locations(id);
