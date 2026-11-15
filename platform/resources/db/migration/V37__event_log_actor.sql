-- The backoffice's events go to the log too, the record of who changed what: they come from no
-- guest's session, so `session_id` becomes optional, and `actor` names the operator token that
-- made the change, empty for a guest's event.
--
-- SQLite cannot drop a NOT NULL from a column, so the table is rebuilt. It has no indexes, and the
-- change data capture filter does not match it.

CREATE TABLE event_log_new (
    id TEXT PRIMARY KEY,
    event_type TEXT NOT NULL,
    event_data TEXT NOT NULL,
    session_id TEXT,
    user_id TEXT,
    actor TEXT,
    occurred_at TEXT NOT NULL,
    created_at TEXT NOT NULL
);

INSERT INTO event_log_new (id, event_type, event_data, session_id, user_id, occurred_at, created_at)
SELECT id, event_type, event_data, session_id, user_id, occurred_at, created_at FROM event_log;

DROP TABLE event_log;

ALTER TABLE event_log_new RENAME TO event_log;
