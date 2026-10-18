ALTER TABLE sessions ADD COLUMN current_advertiser_id TEXT;
ALTER TABLE surveys ADD COLUMN video_id TEXT REFERENCES advertiser_videos(id);
