#!/bin/sh
set -e

# Discover private IP from ECS Fargate metadata endpoint
PRIVATE_IP=$(wget -qO- "$ECS_CONTAINER_METADATA_URI_V4" \
  | grep -oE '"IPv4Addresses":\["[0-9.]+"' \
  | grep -oE '[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+')

if [ -z "$PRIVATE_IP" ]; then
  echo "ERROR: Could not discover private IP from ECS metadata"
  exit 1
fi

echo "Discovered private IP: $PRIVATE_IP"

# ─── One-time migration: nuke broken state on v4 first boot ──────────────────
# v1, v2, v3 all targeted /rqlite/file/data/raft as a directory — but rqlite v9
# stores the Raft log as a single Bolt DB FILE at /rqlite/file/data/raft.db.
# The `rm -rf` against a non-existent path was a silent no-op, so every prior
# migration ran the echo but kept the corrupt 2.2k-entry log. v4 wipes the
# actual v9 paths (raft.db + rsnapshots/ + db.sqlite3) so the cluster finally
# starts truly fresh. Make SURE to delete the S3 backup too before deploying,
# otherwise -auto-restore brings the corruption back.
MIGRATION_MARKER=/rqlite/file/data/.clean-bootstrap-v4
if [ "${RQLITE_BOOTSTRAP_EXPECT:-3}" = "1" ] && [ ! -f "$MIGRATION_MARKER" ]; then
  echo "Migration v4: nuking rqlite v9 state files for clean bootstrap"
  rm -rf /rqlite/file/data/raft.db \
         /rqlite/file/data/raft.db-wal \
         /rqlite/file/data/raft.db-shm \
         /rqlite/file/data/rsnapshots \
         /rqlite/file/data/snapshots \
         /rqlite/file/data/db.sqlite3 \
         /rqlite/file/data/db.sqlite3-wal \
         /rqlite/file/data/db.sqlite3-shm \
         /rqlite/file/data/peers.recovered.json \
         /rqlite/file/data/.dns-identity-migrated \
         /rqlite/file/data/.dns-identity-migrated-v2 \
         /rqlite/file/data/.clean-bootstrap-v3
  mkdir -p /rqlite/file/data
  echo "Migration v4: state directory now:"
  ls -la /rqlite/file/data/ || true
  touch "$MIGRATION_MARKER"
fi

# Generate auto-backup + auto-restore config. continue_on_failure=true makes
# auto-restore best-effort: if the S3 backup is missing (first boot, prior
# wipe, etc.), rqlite bootstraps with an empty DB instead of refusing to
# start. interval/vacuum are auto-backup fields; continue_on_failure applies
# only to the restore side.
BACKUP_CFG=/tmp/backup.json
cat > "$BACKUP_CFG" <<EOF
{
  "version": 1,
  "type": "s3",
  "interval": "${RQLITE_BACKUP_INTERVAL:-5m}",
  "vacuum": false,
  "continue_on_failure": true,
  "sub": {
    "region": "$RQLITE_BACKUP_REGION",
    "bucket": "$RQLITE_BACKUP_BUCKET",
    "path": "backup/db.sqlite3.gz"
  }
}
EOF

# ─── Single-node membership recovery via peers.json ─────────────────────────
# rqlite's peers.json mechanism rewrites the persisted Raft membership on
# startup. Writing it on every boot keeps a single-node cluster's membership
# pinned to its DNS identity, which heals state corruption from prior failed
# deploys (e.g. hostname-based IDs left behind by buggy entrypoints). The
# membership is trivially idempotent — same single voter applied repeatedly.
# For multi-node, peers must NOT be hardcoded; the cluster builds the list
# through disco + bootstrap-expect.
if [ "${RQLITE_BOOTSTRAP_EXPECT:-3}" = "1" ]; then
  mkdir -p /rqlite/file/data
  cat > /rqlite/file/data/peers.json <<EOF
[
  {
    "id": "${RQLITE_DISCO_DNS_NAME}",
    "address": "${RQLITE_DISCO_DNS_NAME}:${RQLITE_RAFT_PORT:-4002}",
    "non_voter": false
  }
]
EOF
fi

# ─── Identity resolution + run args ──────────────────────────────────────────
# Single-node: stable DNS identity via CloudMap. The A record `rqlite.captal.local`
# always resolves to the current task's IP, so the persisted Raft state (which
# stores the adv-addr as a string) keeps working across task replacements.
# NO -disco-mode: with bootstrap-expect=1, disco would resolve the DNS to the
# node's own IP and trigger a self-join loop ("no leader"). Standalone bootstrap
# is the right primitive for a single-node cluster.
#
# Multi-node: per-task identity (HOSTNAME + PRIVATE_IP) — each Raft member is a
# distinct entity. -disco-mode dns lets peers find each other through CloudMap
# MULTIVALUE A records, and -bootstrap-expect blocks the first quorum.
if [ "${RQLITE_BOOTSTRAP_EXPECT:-3}" = "1" ]; then
  NODE_ID="$RQLITE_DISCO_DNS_NAME"
  HTTP_ADV_ADDR="$RQLITE_DISCO_DNS_NAME:${RQLITE_HTTP_PORT:-4001}"
  RAFT_ADV_ADDR="$RQLITE_DISCO_DNS_NAME:${RQLITE_RAFT_PORT:-4002}"

  set -- \
    -node-id "$NODE_ID" \
    -http-addr "0.0.0.0:${RQLITE_HTTP_PORT:-4001}" \
    -http-adv-addr "$HTTP_ADV_ADDR" \
    -raft-addr "0.0.0.0:${RQLITE_RAFT_PORT:-4002}" \
    -raft-adv-addr "$RAFT_ADV_ADDR"
else
  NODE_ID="$HOSTNAME"
  HTTP_ADV_ADDR="$PRIVATE_IP:${RQLITE_HTTP_PORT:-4001}"
  RAFT_ADV_ADDR="$PRIVATE_IP:${RQLITE_RAFT_PORT:-4002}"

  set -- \
    -node-id "$NODE_ID" \
    -http-addr "0.0.0.0:${RQLITE_HTTP_PORT:-4001}" \
    -http-adv-addr "$HTTP_ADV_ADDR" \
    -raft-addr "0.0.0.0:${RQLITE_RAFT_PORT:-4002}" \
    -raft-adv-addr "$RAFT_ADV_ADDR" \
    -disco-mode dns \
    -disco-config "{\"name\":\"$RQLITE_DISCO_DNS_NAME\"}" \
    -bootstrap-expect "$RQLITE_BOOTSTRAP_EXPECT" \
    -raft-reap-node-timeout "${RQLITE_REAP_TIMEOUT:-120s}"
fi

exec rqlited "$@" \
  -extensions-path=/opt/extensions/sqlean.zip,/opt/extensions/misc.zip \
  -auto-backup "$BACKUP_CFG" \
  -auto-restore "$BACKUP_CFG" \
  /rqlite/file/data
