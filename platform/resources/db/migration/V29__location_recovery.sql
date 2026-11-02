-- What a location makes of an error and of a playback its page never ended: the wait for a way out
-- of a client error, and the grace a playback is given after its file would have finished.
--
-- One row per location that changed any of it. A NULL column, or no row at all, is the value the
-- code holds as its default, so a location that never set anything follows the default as it
-- changes. Durations are in seconds.

CREATE TABLE location_recovery (
    location_id TEXT PRIMARY KEY REFERENCES locations(id),
    maximum_seconds INTEGER,
    ceiling_seconds INTEGER,
    zero_below_seconds INTEGER,
    video_weight REAL,
    video_answer_weight REAL,
    identification_weight REAL,
    email_weight REAL,
    identified_factor REAL,
    watched_factor REAL,
    recovery_exponent REAL,
    fabricated_factor REAL,
    recurrent_factor REAL,
    recovery_window_seconds INTEGER,
    recurrence_window_seconds INTEGER,
    playback_grace_seconds INTEGER,
    updated_at TEXT NOT NULL
);
