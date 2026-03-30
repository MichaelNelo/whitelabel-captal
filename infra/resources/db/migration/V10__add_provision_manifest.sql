CREATE TABLE provision_manifest (
    entity_key TEXT PRIMARY KEY,
    location_id TEXT,
    content_hash TEXT NOT NULL,
    provisioned_at TEXT NOT NULL
);
