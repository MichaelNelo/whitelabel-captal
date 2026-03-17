CREATE TABLE event_log (
    id TEXT PRIMARY KEY,
    event_type TEXT NOT NULL,
    event_data TEXT NOT NULL,
    session_id TEXT NOT NULL,
    user_id TEXT,
    occurred_at TEXT NOT NULL,
    created_at TEXT NOT NULL
);
