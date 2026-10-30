-- A playback the backoffice closed because its page never said it ended: the length of its file
-- plus a margin went by with it still open. Closed rather than deleted, so how often a page dies
-- without `pagehide` reaching the server stays countable.

ALTER TABLE video_playbacks ADD COLUMN expired INTEGER NOT NULL DEFAULT 0;
