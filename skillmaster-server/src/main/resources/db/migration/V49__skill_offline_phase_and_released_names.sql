-- v61: a public skill with people using it leaves through a 30-day ramp, and deleting a skill
-- releases its name while the row stays for restore (ADR 0183, ADR 0184).

-- `offline_at` keeps meaning "already offline". The ramp is two more columns rather than a status
-- enum: the state is derived from timestamps, the same way an invitation is expired by reading
-- `expires_at` rather than by a sweep (V34).
ALTER TABLE skill ADD COLUMN offline_pending_at TEXT;
ALTER TABLE skill ADD COLUMN offline_due_at     TEXT;
ALTER TABLE skill ADD CONSTRAINT skill_offline_phase CHECK (
  (offline_pending_at IS NULL AND offline_due_at IS NULL)
  OR (offline_pending_at IS NOT NULL AND offline_due_at IS NOT NULL AND offline_at IS NULL)
);

COMMENT ON COLUMN skill.offline_pending_at IS
  'RFC3339 UTC. When the 30-day 下线中 ramp began; NULL when the skill is online or already offline.';
COMMENT ON COLUMN skill.offline_due_at IS
  'RFC3339 UTC. When the ramp ends. Read as offline from this moment even before the daily job materialises `offline_at`.';

-- **The name belongs to live skills only.** A soft-deleted row still exists for restore, but it
-- must not hold the name against a new skill (ADR 0183). `insertNew`'s ON CONFLICT names this
-- index, so the predicate has to travel with it.
ALTER TABLE skill DROP CONSTRAINT skill_namespace_id_name_key;
CREATE UNIQUE INDEX skill_live_name ON skill (namespace_id, name)
  WHERE deleted_at IS NULL;

-- The daily job looks for due rows; the read paths compute the same predicate, so this index is
-- only for the job and any "is there anything due" health query.
CREATE INDEX skill_offline_due ON skill (offline_due_at)
  WHERE offline_due_at IS NOT NULL AND offline_at IS NULL AND deleted_at IS NULL;
