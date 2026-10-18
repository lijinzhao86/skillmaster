-- `skill` carries the facts its lists need, so a list no longer joins the content tables.
-- v5, ADR 0053.
--
-- Splitting metadata from content has one price: search and the author list used to read four
-- facts out of `skill_version` — the current version's display fields, its submission time, the
-- draft count, and the newest draft. A statement may name only one module's tables after the
-- split, so those facts are projected onto `skill` and written back by M12 at submit / discard /
-- publish time. This is not a second source of truth: the versions and the bytes stay M7's, and
-- the pointer stays metadata's (ADR 0053 §决定 3). It is the copy a list reads so it does not have
-- to cross the boundary.
--
-- `published` gets no new column: `current_version_id IS NOT NULL` is it. The backfill below is
-- the only read of `skill_version` a migration performs; after this, no list statement names it.
ALTER TABLE skill ADD COLUMN when_to_use                  TEXT;
ALTER TABLE skill ADD COLUMN current_version_name         TEXT;
ALTER TABLE skill ADD COLUMN current_digest               TEXT;
ALTER TABLE skill ADD COLUMN current_version_submitted_at TEXT;
ALTER TABLE skill ADD COLUMN draft_count                  INTEGER NOT NULL DEFAULT 0;
ALTER TABLE skill ADD COLUMN newest_draft_submitted_at    TEXT;
ALTER TABLE skill ADD COLUMN newest_draft_version         TEXT;
ALTER TABLE skill ADD COLUMN newest_draft_digest          TEXT;
ALTER TABLE skill ADD COLUMN newest_title                 TEXT;
ALTER TABLE skill ADD COLUMN newest_description           TEXT;

COMMENT ON COLUMN skill.current_version_submitted_at IS
  'RFC3339 UTC. submitted_at of the version current_version_id points to; NULL until something is published. Written by M12 recordPublish.';
COMMENT ON COLUMN skill.newest_draft_submitted_at IS
  'RFC3339 UTC. submitted_at of the newest draft; NULL when there is no draft. Recomputed by M12 recordSubmission / recordDiscard / recordPublish.';
COMMENT ON COLUMN skill.when_to_use IS
  'metadata.when_to_use of the current version, extracted from its frontmatter on publish. The listing reads this, not the version table.';

-- The current published version: its display fields and submission time. The three display columns
-- are rewritten even though a publish already kept them in step, so that the row's copy is the
-- backfill's answer for every row rather than "whatever the older code happened to leave here".
-- `when_to_use` falls back to the top-level key, where older frontmatter declared it.
UPDATE skill s SET
  title = v.title,
  description = v.description,
  frontmatter = v.frontmatter,
  when_to_use = COALESCE(v.frontmatter::jsonb #>> '{metadata,when_to_use}',
                         v.frontmatter::jsonb ->> 'when_to_use'),
  current_version_name = v.version,
  current_digest = v.digest,
  current_version_submitted_at = v.submitted_at
FROM skill_version v
WHERE v.id = s.current_version_id;

-- The draft side: how many are waiting, and the newest one's facts. Read in one aggregate pass
-- ordered by `number` (submission order) so the name and the digest always come from the same
-- draft, and two submissions in the same second cannot tie.
UPDATE skill s SET
  draft_count = d.drafts,
  newest_draft_submitted_at = d.newest_submitted_at,
  newest_draft_version = d.newest_version,
  newest_draft_digest = d.newest_digest,
  newest_title = d.newest_title,
  newest_description = d.newest_description
FROM (
  SELECT skill_id,
         count(*) AS drafts,
         (array_agg(submitted_at ORDER BY number DESC))[1] AS newest_submitted_at,
         (array_agg(version      ORDER BY number DESC))[1] AS newest_version,
         (array_agg(digest       ORDER BY number DESC))[1] AS newest_digest,
         (array_agg(title        ORDER BY number DESC))[1] AS newest_title,
         (array_agg(description  ORDER BY number DESC))[1] AS newest_description
  FROM skill_version
  WHERE state = 'draft'
  GROUP BY skill_id
) d
WHERE d.skill_id = s.id;

-- No index. Every column here is read from a row the query has already found by address or by the
-- access predicate; none of them narrows a scan.
