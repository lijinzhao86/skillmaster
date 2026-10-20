-- A published version can be taken down without deleting it, and it keeps the date it first went
-- live. v6, ADR 0058, ADR 0059.
--
-- Until v6 every published row was readable by every caller that could see the skill: "published"
-- meant "will serve". ADR 0058 splits the two — a maintain can revoke one version while keeping the
-- row, its files and its history, so the state table gains a fourth value. `state` stays TEXT and
-- the V10 pairing constraint still holds: a non-draft row always has a `state_at`.
--
-- `first_published_at` is a second date because `state_at` now moves. In v6 `state_at` records the
-- latest transition (revoke, restore or rollback), and the consumer plane's `publishedAt` must keep
-- reporting when the version first went live. Reusing `state_at` there would make a restored
-- version look freshly published.
--
-- `revoked_reason` is optional (ADR 0059): the constraint only says a reason can exist on a revoked
-- row and nowhere else. The consumer plane never returns it.
ALTER TABLE skill_version ADD COLUMN revoked_reason TEXT;
ALTER TABLE skill_version ADD COLUMN first_published_at TEXT;

-- Backfill before adding the paired constraint: after V10 a published row's `state_at` is exactly
-- the moment it left draft, which is the first publish. Revoked rows did not exist yet.
UPDATE skill_version SET first_published_at = state_at WHERE state = 'published';

ALTER TABLE skill_version ADD CONSTRAINT skill_version_state_known
  CHECK (state IN ('draft', 'published', 'discarded', 'revoked'));
ALTER TABLE skill_version ADD CONSTRAINT skill_version_revoked_reason_paired
  CHECK (revoked_reason IS NULL OR state = 'revoked');
ALTER TABLE skill_version ADD CONSTRAINT skill_version_first_published_paired
  CHECK ((state IN ('published', 'revoked')) = (first_published_at IS NOT NULL));

COMMENT ON COLUMN skill_version.state IS
  'draft until someone publishes or discards it, then published, revoked or discarded. Not derivable: a draft and a superseded version are both non-current, and discarded has no timestamp expression (ADR 0031). `revoked` keeps the row and its files; it only stops callers reading it (ADR 0058).';
COMMENT ON COLUMN skill_version.state_at IS
  'When the row last changed state: first publish, revoke, restore, rollback or discard. NULL while draft. Kept paired with state by skill_version_state_at_paired. The first publish is first_published_at, not this column.';
COMMENT ON COLUMN skill_version.revoked_reason IS
  'Optional note a maintain wrote when revoking. Cleared on restore. Author plane only — the consumer plane answers 410 without repeating it (ADR 0059).';
COMMENT ON COLUMN skill_version.first_published_at IS
  'When this version first became published. Never moves: not on rollback, revoke or restore. The consumer plane''s publishedAt reads this, so a restored version does not look newly published.';
