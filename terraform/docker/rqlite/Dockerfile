# Pinned, and to the version already deployed rather than to a choice: this said `:latest`, so the
# database engine moved a major version on its own. It was written when `latest` was the 8 line and
# today it resolves to 10.6.1 -- nobody decided that, and nothing anywhere would have said so.
#
# Deliberately not pinned *down* to the 8.36.2 the test suite runs. Downgrading rqlite across two
# major versions against an existing data directory is not something to discover in production; the
# suite is the side that has to move. Until it does, at least the divergence is a known one.
#
# Bumping this means bumping `io.rqlite:rqlite-jdbc` with it. The driver in the application repo is
# 9.1.2.1, which predates the release that made failed statements throw instead of being silently
# ignored -- so against this server a statement the database refuses raises nothing here, while the
# server has carried structured error codes since v10.6.0 that only driver 10.5.2.1 can read.
FROM rqlite/rqlite:10.6.1

COPY --chmod=755 entrypoint.sh /usr/local/bin/entrypoint.sh

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
