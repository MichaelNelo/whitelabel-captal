-- One row per deploy, pause, resume or destroy of a location's portal.
--
-- Those operations wait minutes on AWS, longer than a host keeps one tool call open, so the tool
-- answers with the run's id at once and the work goes on in the background. This is what the
-- work reports into and what `get_deploy_status` reads, so a run outlives the call that started it
-- and can be read back by whoever asks next.

CREATE TABLE mcp_deploy_runs (
    id TEXT PRIMARY KEY,

    slug TEXT NOT NULL,

    -- deploy, pause, resume or destroy.
    operation TEXT NOT NULL,

    -- running, succeeded or failed. A run whose process died stays `running` here; readers tell it
    -- apart by `heartbeat_at` and report it as interrupted.
    status TEXT NOT NULL,

    -- Every step in order, as JSON: name, state (pending, running, done, failed) and an optional
    -- detail. The list is fixed when the run starts, so a reader can show what is still to come.
    steps TEXT NOT NULL,

    -- The operation's own response as JSON, once it succeeded.
    result TEXT,

    -- Why it failed, once it did.
    error TEXT,

    started_by TEXT NOT NULL REFERENCES mcp_tokens(id),
    started_at TEXT NOT NULL,

    -- Rewritten every few seconds while the work is alive, which is the only sign it still is.
    heartbeat_at TEXT NOT NULL,

    finished_at TEXT
);

-- The latest run of a location, and whether one is still going, are the two questions asked.
CREATE INDEX idx_mcp_deploy_runs_slug ON mcp_deploy_runs(slug, started_at);
