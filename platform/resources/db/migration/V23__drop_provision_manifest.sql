-- Drops the table the file-provisioning diff kept its state in.
--
-- `provision_manifest` recorded a content hash per provisioned entity, and `ProvisionService`
-- diffed it three ways against a directory of YAML to decide what to create, update or delete.
-- That engine is gone: content is written through the MCP backoffice and the API only reads.
--
-- Dropped rather than left empty, because a populated manifest with no engine is a loaded gun.
-- `Delete = localKeys -- diskEntries`, so anything still listed here would be scheduled for
-- deletion the moment someone pointed a provisioning run at an empty or partial directory — and
-- every upsert hardcoded `is_active = 1`, so the same run would also undo every deliberate
-- retirement. Keeping the rows made reviving the engine quietly destructive; keeping the empty
-- table made it look supported.
--
-- Nothing reads it. `ProvisionManifestRow` and its `SchemaMeta` go with this migration.

DROP TABLE IF EXISTS provision_manifest;
