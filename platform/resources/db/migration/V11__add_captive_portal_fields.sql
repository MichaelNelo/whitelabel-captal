ALTER TABLE sessions ADD COLUMN client_mac TEXT NOT NULL DEFAULT '';
ALTER TABLE sessions ADD COLUMN ap_mac TEXT NOT NULL DEFAULT '';
ALTER TABLE sessions ADD COLUMN redirect_url TEXT NOT NULL DEFAULT '';
ALTER TABLE sessions ADD COLUMN ssid TEXT NOT NULL DEFAULT '';

ALTER TABLE locations ADD COLUMN ap_mac TEXT;

CREATE INDEX idx_sessions_client_mac ON sessions(client_mac);
