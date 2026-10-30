-- A device is what UniFi's redirect says about it, not what its browser calls itself.
--
-- Its id was a hash of the User-Agent, so every phone of one model, system and browser version was
-- the same "device". It is now looked up by fingerprint: the client MAC UniFi authorizes, and the
-- click id of the link shortener in front of the portal, which tracks devices. Which of the two a
-- match compares is configuration. Rows from before keep their ids and have neither, so nothing
-- matches them and they are left to age out.

ALTER TABLE devices ADD COLUMN client_mac TEXT NOT NULL DEFAULT '';
ALTER TABLE devices ADD COLUMN click_id TEXT NOT NULL DEFAULT '';

CREATE INDEX idx_devices_client_mac ON devices(client_mac);
CREATE INDEX idx_devices_click_id ON devices(click_id);

-- What a user did, and on which device: trust in a returning user is earned per device, so
-- somebody typing another person's email on their own laptop starts with none. Sessions are to be
-- cleaned up in time, and these rows have to outlive them. NULL on everything recorded before.
ALTER TABLE answers ADD COLUMN device_id TEXT REFERENCES devices(id);
ALTER TABLE video_views ADD COLUMN device_id TEXT REFERENCES devices(id);

CREATE INDEX idx_answers_user_device ON answers(user_id, device_id);
CREATE INDEX idx_video_views_user_device ON video_views(user_id, device_id);
