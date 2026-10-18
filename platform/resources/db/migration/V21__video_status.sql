-- A video's lifecycle is three states, and `is_active` could only express two.
--
-- Created, never uploaded, and retired from rotation all collapsed into is_active = 0. That is not
-- a cosmetic overload: `resume_video_upload` offers to upload anything inactive, and
-- `confirm_video_upload` finds the object and flips it active — so a retired video that still has
-- its file could be put back into rotation through a path meant for uploads, without anyone asking.
--
-- Modelling it as two flags (is_active, has a URL) would leave a fourth combination that cannot
-- happen and nothing to stop it. One column with a CHECK makes the impossible state
-- unrepresentable, and the database enforces it rather than convention.
--
-- `video_url` stays NOT NULL. It is where the S3 key lives — `AssetStore.keyOf` recovers the key by
-- parsing the URL, and nothing else stores it — so a URL that is null until the file arrives would
-- take the key with it. The URL is known at creation anyway: the server chooses the key
-- (`<slug>/videos/<videoId>.<ext>`) rather than taking the uploader's file name.

ALTER TABLE advertiser_videos
    ADD COLUMN status TEXT NOT NULL DEFAULT 'active'
    CHECK (status IN ('pending', 'active', 'retired'));

-- Every inactive row becomes 'retired'. It cannot be better than that: telling a pending video from
-- a retired one needs to know whether the object exists in S3, which a migration cannot ask.
--
-- In production this is correct by construction — the only thing that has ever written
-- is_active = 0 to this table is the provisioning soft-delete, and the server that creates pending
-- videos has never run there. A development database seeded through the MCP server will have
-- pending videos marked retired; `resume_video_upload` refuses them, which is the safe direction,
-- and re-creating them costs nothing.
UPDATE advertiser_videos
SET status = CASE WHEN is_active = 1 THEN 'active' ELSE 'retired' END;

-- Before the column, and this ordering is the whole point: V4 created this index over `is_active`,
-- and SQLite refuses to drop a column an index still references.
--
-- It did not always refuse. On SQLite 3.46, which rqlite 8.36 ships, the DROP COLUMN went through
-- and took the index with it -- so this migration applied cleanly for its whole life. On 3.53, in
-- rqlite 10.6, the same statement fails with "error in index idx_advertiser_videos_active after
-- drop column: no such column: is_active", and every fresh database stops here.
--
-- Nothing saw it coming because the old JDBC driver reported a refused statement as zero affected
-- rows inside an HTTP 200, raising nothing: against a 10.6 server it failed and said it had
-- succeeded. The driver bump to 10.5.2.1 is what made the error visible, not what caused it.
--
-- `IF EXISTS` so this is replayable on a database where the old behaviour already removed it.
DROP INDEX IF EXISTS idx_advertiser_videos_active;

-- Dropped rather than kept in sync. Two sources of truth for the same fact is how they drift, and
-- removing the column makes the compiler enumerate every reader instead of leaving one behind.
ALTER TABLE advertiser_videos DROP COLUMN is_active;

CREATE INDEX idx_advertiser_videos_status ON advertiser_videos(status);
