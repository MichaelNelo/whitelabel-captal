-- Renames the two video types to the words the domain already uses: `ad` and `promo`.
--
-- `VideoType` has read `Ad` and `Promo` since it was written; `toDbString` translated them to
-- `publicidad` and `propaganda` on the way out. V5 then renamed `last_propaganda_video_id` to
-- `last_promo_video_id` and left the values alone, so the column says promo and its contents say
-- propaganda. Anyone writing SQL against this table has to know that.
--
-- A rebuild rather than an UPDATE, because the CHECK names the old values and SQLite cannot alter
-- a constraint in place. An UPDATE would be rejected by it — and rqlite reports a rejected
-- statement inside an HTTP 200, so the rename would appear to succeed and change nothing.
--
-- The incoming foreign keys (sessions, video_views, surveys) are not rewritten: ids do not change,
-- and SQLite does not enforce references by default here.

CREATE TABLE advertiser_videos_new (
    id TEXT PRIMARY KEY,
    advertiser_id TEXT REFERENCES advertisers(id),
    video_type TEXT NOT NULL CHECK (video_type IN ('ad', 'promo')),
    video_url TEXT NOT NULL,
    duration_seconds INTEGER NOT NULL,
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
    id, advertiser_id, video_type, video_url, duration_seconds, min_watch_seconds,
    show_countdown, no_repeat_seconds, priority, created_at, updated_at,
    location_id, product_campaign_id, status
)
SELECT
    id,
    advertiser_id,
    CASE video_type WHEN 'publicidad' THEN 'ad' WHEN 'propaganda' THEN 'promo' ELSE video_type END,
    video_url,
    duration_seconds,
    min_watch_seconds,
    show_countdown,
    no_repeat_seconds,
    priority,
    created_at,
    updated_at,
    location_id,
    product_campaign_id,
    status
FROM advertiser_videos;

DROP TABLE advertiser_videos;

ALTER TABLE advertiser_videos_new RENAME TO advertiser_videos;

-- The indexes go with the dropped table and have to be recreated by hand.
CREATE INDEX idx_advertiser_videos_advertiser ON advertiser_videos(advertiser_id);
CREATE INDEX idx_advertiser_videos_type ON advertiser_videos(video_type);
CREATE INDEX idx_advertiser_videos_status ON advertiser_videos(status);
