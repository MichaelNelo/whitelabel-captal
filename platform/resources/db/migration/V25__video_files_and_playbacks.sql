-- A video's file becomes a row of its own, and every guest playback a row too.
--
-- Replacing a file used to mean overwriting the object under the same key. A guest's player fetches
-- a video in ranges for as long as it plays, so the ranges after the overwrite came from the new
-- file. Each file now has its own key and its own row, the current one being the newest confirmed;
-- a replacement is a new row, and the old object stays until no playback that started on it is
-- still open.
--
-- The first file of every existing video takes the video's own id, which is also the last segment
-- of its key (`<slug>/videos/<videoId>`), so keys stay what they are.

CREATE TABLE video_files (
    id TEXT PRIMARY KEY,
    video_id TEXT NOT NULL REFERENCES advertiser_videos(id),
    url TEXT NOT NULL UNIQUE,
    -- Read from the file when it is confirmed; NULL until then.
    duration_seconds INTEGER,
    created_at TEXT NOT NULL,
    confirmed_at TEXT,
    deleted_at TEXT
);

CREATE INDEX idx_video_files_video ON video_files(video_id);

INSERT INTO video_files (id, video_id, url, duration_seconds, created_at, confirmed_at, deleted_at)
SELECT
    id,
    id,
    video_url,
    CASE status WHEN 'pending' THEN NULL ELSE duration_seconds END,
    created_at,
    CASE status WHEN 'pending' THEN NULL ELSE updated_at END,
    NULL
FROM advertiser_videos;

-- Open while `ended_at` is NULL. A file with an open playback is never deleted.
CREATE TABLE video_playbacks (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES sessions(id),
    file_id TEXT NOT NULL REFERENCES video_files(id),
    started_at TEXT NOT NULL,
    ended_at TEXT
);

CREATE INDEX idx_video_playbacks_file ON video_playbacks(file_id);
CREATE INDEX idx_video_playbacks_session ON video_playbacks(session_id);

-- The URL and the length now belong to the file. A rebuild, as in V22: a column cannot be dropped
-- from a table other tables reference.
CREATE TABLE advertiser_videos_new (
    id TEXT PRIMARY KEY,
    advertiser_id TEXT REFERENCES advertisers(id),
    video_type TEXT NOT NULL CHECK (video_type IN ('ad', 'promo')),
    min_watch_seconds INTEGER NOT NULL DEFAULT 8,
    show_countdown INTEGER NOT NULL DEFAULT 1,
    no_repeat_seconds INTEGER,
    priority INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    location_id TEXT REFERENCES locations(id),
    product_campaign_id TEXT,
    status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'active', 'retired'))
);

INSERT INTO advertiser_videos_new (
    id, advertiser_id, video_type, min_watch_seconds, show_countdown, no_repeat_seconds, priority,
    created_at, updated_at, location_id, product_campaign_id, status
)
SELECT
    id, advertiser_id, video_type, min_watch_seconds, show_countdown, no_repeat_seconds, priority,
    created_at, updated_at, location_id, product_campaign_id, status
FROM advertiser_videos;

DROP TABLE advertiser_videos;

ALTER TABLE advertiser_videos_new RENAME TO advertiser_videos;

CREATE INDEX idx_advertiser_videos_advertiser ON advertiser_videos(advertiser_id);
CREATE INDEX idx_advertiser_videos_type ON advertiser_videos(video_type);
CREATE INDEX idx_advertiser_videos_status ON advertiser_videos(status);
