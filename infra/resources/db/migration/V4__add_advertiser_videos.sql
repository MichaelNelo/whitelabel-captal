-- Tabla de anunciantes
CREATE TABLE advertisers (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    priority INTEGER NOT NULL DEFAULT 0,
    is_active INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE INDEX idx_advertisers_priority ON advertisers(priority);
CREATE INDEX idx_advertisers_active ON advertisers(is_active);

-- Tabla principal de videos
CREATE TABLE advertiser_videos (
    id TEXT PRIMARY KEY,
    advertiser_id TEXT REFERENCES advertisers(id),
    video_type TEXT NOT NULL CHECK (video_type IN ('publicidad', 'propaganda')),
    video_url TEXT NOT NULL,
    duration_seconds INTEGER NOT NULL,
    min_watch_seconds INTEGER NOT NULL DEFAULT 8,
    show_countdown INTEGER NOT NULL DEFAULT 1,
    no_repeat_seconds INTEGER,
    is_active INTEGER NOT NULL DEFAULT 1,
    priority INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE INDEX idx_advertiser_videos_advertiser ON advertiser_videos(advertiser_id);
CREATE INDEX idx_advertiser_videos_type ON advertiser_videos(video_type);
CREATE INDEX idx_advertiser_videos_active ON advertiser_videos(is_active);

-- Registro de visualizaciones
CREATE TABLE video_views (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES sessions(id),
    user_id TEXT REFERENCES users(id),
    video_id TEXT NOT NULL REFERENCES advertiser_videos(id),
    duration_watched_seconds INTEGER NOT NULL,
    completed INTEGER NOT NULL,
    viewed_at TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE INDEX idx_video_views_session ON video_views(session_id);
CREATE INDEX idx_video_views_user ON video_views(user_id);
CREATE INDEX idx_video_views_user_video ON video_views(user_id, video_id);

-- Agregar campos para videos en sessions
ALTER TABLE sessions ADD COLUMN current_video_id TEXT REFERENCES advertiser_videos(id);
ALTER TABLE sessions ADD COLUMN last_propaganda_video_id TEXT REFERENCES advertiser_videos(id);
