-- Submitting a version and publishing it are now two different actions.
--
-- Until now uploading a zip was also what made it live: the version row and the current-version
-- pointer moved in one transaction, so "this version is the one consumers get" was a by-product of
-- an upload. ADR 0031 splits them, and that needs a fact this table never had — whether a version
-- has been published at all. It cannot be inferred from the pointer: a draft and a superseded
-- version both fail to be the current one, for different reasons, and "discarded" has no expression
-- in terms of a pointer or a timestamp.
--
-- `state` is written explicitly rather than derived for the same reason ADR 0029 put revocation on
-- the parent row: an invariant maintained by inference is one that leaks quietly.
ALTER TABLE skill_version ADD COLUMN state    TEXT NOT NULL DEFAULT 'draft';
ALTER TABLE skill_version ADD COLUMN state_at TEXT;

-- The version's own metadata, because a version stops being able to borrow the skill row's copy.
-- `skill.title/description/frontmatter` become a projection of whichever version is current — what
-- search reads on the hot path — and only publishing writes them. Without this column set,
-- publishing a draft could not restore the metadata that publishing it is supposed to expose, and
-- submitting a draft would have to overwrite the live skill's title and description to keep them
-- anywhere at all. That would be a submission changing what the consumption plane sees, which is
-- exactly what the split exists to prevent.
ALTER TABLE skill_version ADD COLUMN title       TEXT NOT NULL DEFAULT '';
ALTER TABLE skill_version ADD COLUMN description TEXT NOT NULL DEFAULT '';
ALTER TABLE skill_version ADD COLUMN frontmatter TEXT NOT NULL DEFAULT '{}';

-- `published_*` named the right event while the two actions were one. It is set when a version is
-- submitted, and being published is now a separate thing this table records in `state`, so the old
-- name would describe the wrong event — which is worse than no name at all (the same reason V8
-- exists).
ALTER TABLE skill_version RENAME COLUMN published_by TO submitted_by;
ALTER TABLE skill_version RENAME COLUMN published_at TO submitted_at;

-- The two columns are one fact, so they move together: a draft is exactly the version that has not
-- left its initial state.
ALTER TABLE skill_version ADD CONSTRAINT skill_version_state_at_paired
  CHECK ((state = 'draft') = (state_at IS NULL));

-- Before this migration every version was inserted and made current in the same transaction, so
-- every one of them has been live. Leaving them at the column default would make each existing `@N`
-- address stop resolving, and holding an address stable is the entire point of ADR 0012.
UPDATE skill_version SET state = 'published', state_at = submitted_at;

-- The three metadata columns' authority has been the skill row. For each skill's current version
-- that copy is exactly right, and for the rest it is the best answer that exists: a version's own
-- frontmatter is recoverable only from its SKILL.md bytes, and no database was ever deployed under
-- the old model, so a superseded version's stale copy costs nothing anywhere.
UPDATE skill_version v
   SET title = s.title, description = s.description, frontmatter = s.frontmatter
  FROM skill s
 WHERE s.id = v.skill_id;

COMMENT ON TABLE skill_version IS
  'One immutable snapshot: a skill''s Nth distinct file set, with who submitted it and when, plus a state that does change — draft | published | discarded (ADR 0031). The content never changes: state is not part of the digest and does not move an address.';
COMMENT ON COLUMN skill_version.state IS
  'draft until someone publishes or discards it, then published or discarded for good. Not derivable: a draft and a superseded version are both non-current, and discarded has no timestamp expression.';
COMMENT ON COLUMN skill_version.state_at IS
  'When it left draft: the moment it was first published, or the moment it was discarded. NULL while draft. Re-publishing an already-published version does not move it — it records the first time, not the last.';
COMMENT ON COLUMN skill_version.title IS
  'This version''s display title, from its own frontmatter. Copied onto the skill row when this version is published, because search reads it from there.';
COMMENT ON COLUMN skill_version.description IS
  'This version''s description, from its own frontmatter. Copied onto the skill row when published, for the same reason.';
COMMENT ON COLUMN skill_version.frontmatter IS
  'This version''s parsed frontmatter as JSON, unknown fields included. Copied onto the skill row when published.';
COMMENT ON COLUMN skill_version.submitted_by IS
  'The account that submitted it — the system account when the server submits its own gateway skill.';
COMMENT ON COLUMN skill_version.submitted_at IS
  'RFC3339 UTC, and the version''s own timestamp: a replay of identical content keeps the original rather than moving it. Distinct from state_at, which is when it was published.';
COMMENT ON COLUMN skill.current_version_id IS
  'The version consumers currently get, or NULL when nothing has been published yet — which is a normal state for a skill whose versions are all drafts (ADR 0031). Moved only by publishing, and it may move backwards: that is rollback. No foreign key: it points at skill_version, which points back here, so a FK would be circular (3.3).';
COMMENT ON COLUMN skill.title IS
  'Display title; falls back to the name when the frontmatter has none. A projection of the current version''s title, written only when a version is published (ADR 0031).';
COMMENT ON COLUMN skill.description IS
  'Denormalised from the current version''s frontmatter because search reads it on the hot path (3.4). Written only when a version is published (ADR 0031).';
COMMENT ON COLUMN skill.frontmatter IS
  'The current version''s parsed frontmatter as JSON, unknown fields included. M7 has no opinion about its shape (3.3). Written only when a version is published (ADR 0031).';
COMMENT ON COLUMN skill.updated_at IS
  'RFC3339 UTC, moved by publishing (and by a metadata edit, which does not create a version — 4.3). The listing sorts on it.';
