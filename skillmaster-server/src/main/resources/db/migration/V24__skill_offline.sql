-- An owner can stop serving a skill without deleting it. v6.
--
-- This is not a version state: no row in `skill_version` changes, the pointer stays where it is, and
-- restoring service needs no re-publish. It belongs to `skill` because it is a property of the
-- whole skill, and only the owner may set it.
--
-- NULL means online; a timestamp means offline. The column is nullable with no default and no
-- backfill because every existing skill is online.
ALTER TABLE skill ADD COLUMN offline_at TEXT;

COMMENT ON COLUMN skill.offline_at IS
  'RFC3339 UTC. NULL = serving; non-NULL = this skill is offline and the consumer plane answers 503 skill_offline for every read, pinned ones included. Does not change any version state or current_version_id. Author plane and "my skills" keep working.';
