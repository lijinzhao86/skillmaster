-- v54: a copy is a snapshot in the caller's own namespace (ADR 0173).
--
-- **Provenance is skill-level, not version-level.** A copy produces one new skill and one first
-- draft; later versions of that skill are still versions of the copy, so "where this skill came
-- from" is one row for the skill, not one row per version.
--
-- **No foreign keys.** `skill_version` already stores `current_version_id` as a bare id for the
-- same reason: the source may be soft-deleted, renamed or taken down, and the copy is a historical
-- fact that must stay readable. `source_address` is a snapshot of the address at copy time, so it
-- does not follow a later rename either.
--
-- **Deliberately not `skill_source`.** That table means "content came from an external platform"
-- and the refresh job walks it; an internal copy mixed in would make the refresh treat another
-- skill on this platform as an upstream repository.

CREATE TABLE skill_copy (
  skill_id             TEXT PRIMARY KEY,
  -- The first draft the copy created. Unique because one copied version is one new row.
  version_id           TEXT NOT NULL UNIQUE,
  source_skill_id      TEXT NOT NULL,
  source_version_id    TEXT NOT NULL,
  -- `namespace/name` exactly as it read when the copy happened.
  source_address       TEXT NOT NULL,
  -- The source version's name, or `sha256:<digest>` when it had none (ADR 0033).
  source_version_label TEXT NOT NULL,
  copied_by            TEXT NOT NULL,
  copied_at            TEXT NOT NULL
);

CREATE INDEX idx_skill_copy_source ON skill_copy (source_skill_id);

COMMENT ON TABLE skill_copy IS
  'One row per skill created by copying another: the source identity and the address/version as they read at that moment. Read by the information page and `skmr skill show`; never touched by the source refresh job.';
COMMENT ON COLUMN skill_copy.version_id IS
  'The first draft the copy wrote. Not the skill''s current version, and not updated when later versions are published.';
COMMENT ON COLUMN skill_copy.source_address IS
  'A snapshot: `namespace/name` at copy time. The source may be renamed or deleted afterwards; this column does not follow it.';
