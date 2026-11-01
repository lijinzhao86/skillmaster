-- Whether this skill ever had a version go live, on M12's own row (v37, ADR 0127).
--
-- The narrow delete grants a credential one action it did not have since v4: deleting a skill that
-- has never been published. The condition has to sit in the same UPDATE as the delete — a client or
-- a use case that reads "has it been published?" and then deletes leaves a window in which a
-- collaborator publishes. And the condition cannot join `skill_version`, which is M7's table.
--
-- So the fact is projected onto the row M12 owns. `recordPublish` writes it with COALESCE, which is
-- what keeps it the FIRST publish: rollback, revoke, offline and restore all leave it alone, and a
-- second publish does not move it.
ALTER TABLE skill ADD COLUMN ever_published_at TEXT;

-- Backfill from the versions that already went live. `first_published_at` is non-null exactly on
-- published and revoked rows (V23's paired constraint), and it never moves, so the minimum is the
-- first time this skill served anything.
UPDATE skill s
   SET ever_published_at = (
       SELECT MIN(v.first_published_at) FROM skill_version v
        WHERE v.skill_id = s.id AND v.first_published_at IS NOT NULL);

COMMENT ON COLUMN skill.ever_published_at IS
  'When this skill first had a version go live. Never moves and never clears — not on revoke, rollback, offline or restore. Written by recordPublish; the CLI''s narrow delete reads it in the same statement (ADR 0127).';
