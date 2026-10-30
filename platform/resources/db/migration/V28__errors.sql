-- Every error a guest could be offered a way out of, and where that way out stands.
--
-- A server error is recorded where the API catches a defect, and its id goes back to the page; a
-- client error is recorded when the page reports it. Either way the id is what the page leaves
-- through, and what staff look an unrecoverable one up by. Rows outlive the sessions they name.

CREATE TABLE errors (
    id TEXT PRIMARY KEY,
    session_id TEXT,
    device_id TEXT,
    origin TEXT NOT NULL CHECK (origin IN ('server', 'client')),
    message TEXT NOT NULL,
    -- A server error's stack trace; a client error's bundle version and frames, as JSON.
    detail TEXT NOT NULL,
    -- Client errors: what makes two reports the same error, and what the server made of this one.
    fingerprint TEXT,
    verdict TEXT,
    created_at TEXT NOT NULL,
    -- When the session may leave through it; NULL until it was offered.
    available_at TEXT,
    redeemed_at TEXT
);

CREATE INDEX idx_errors_session ON errors(session_id);
CREATE INDEX idx_errors_device ON errors(device_id, redeemed_at);
CREATE INDEX idx_errors_fingerprint ON errors(fingerprint, created_at);
