-- Operator tokens, moved out of `MCP_TOKENS` and into the database.
--
-- The environment variable could only ever be edited by whoever deploys the server, which made
-- handing a translator their own credential a deployment. Operators need to pass tokens to each
-- other, and a token that can only be minted by redeploying is one that gets shared instead.
--
-- This is the MCP server's own schema, with its own Flyway history table. The API owns everything
-- else and migrates it on startup; two processes migrating the same history would race with no
-- upside. Nothing here is referenced by the portal.

CREATE TABLE mcp_tokens (
    id TEXT PRIMARY KEY,

    -- The hash, never the token. A bearer credential in a column is in the Raft log, in every
    -- backup and in any `SELECT *` -- and reading whole tables is how this database gets debugged.
    -- SHA-256 without a salt or a work factor is right here and would not be for a password: the
    -- server generates these with 256 bits of entropy, so there is no dictionary to attack and
    -- nothing to slow down. The plaintext exists once, in the response that creates it.
    token_sha256 TEXT NOT NULL UNIQUE,

    -- Who holds it. Shown in listings and logs so a token can be revoked by name rather than by
    -- guessing which id belongs to whom.
    name TEXT NOT NULL,

    -- Comma-separated, matching the format `MCP_TOKENS` used, so the set is readable in a dump
    -- without joining anything. There is no scopes table: the legal values are an enum in Scala,
    -- and a row per scope would let the database hold combinations the code cannot express.
    scopes TEXT NOT NULL,

    -- The token that minted this one. NULL for the seeded admin, which no token created.
    -- Delegation is attenuating -- a token can only grant scopes it holds -- so this is the record
    -- of who vouched for whom, and what `revoke_token` checks before letting one revoke another.
    created_by TEXT REFERENCES mcp_tokens(id),

    created_at TEXT NOT NULL,

    -- Soft, like everything else here. A revoked token has to stay: `created_by` points at it from
    -- the tokens it minted, and deleting it would orphan that record exactly when someone is
    -- investigating why those tokens exist.
    revoked_at TEXT
);

-- Every request looks a token up by hash, so this is the hot path and the only one.
CREATE INDEX idx_mcp_tokens_sha256 ON mcp_tokens(token_sha256);
CREATE INDEX idx_mcp_tokens_created_by ON mcp_tokens(created_by);
