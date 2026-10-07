-- Sharing is a relationship between one skill and one account, not a membership of a namespace.
--
-- ADR 0034. `namespace_member` answers "who may enter my namespace"; this answers "who may read this
-- one skill". The two look similar and are not: sharing one skill through the membership table would
-- hand the grantee every skill the owner has, which is a different product decision, and the one
-- that actually needs an organisation model. v1 defers membership management; it does not need to
-- defer this.
--
-- The grant hangs off `skill.id` and not off the address `(namespace_id, name)`. ADR 0004 states the
-- reason in its own words: renaming would then be "swapping the skill for a different one", and
-- versions, install records, audit rows "and future entitlement records" would all have to follow.
-- This is the entitlement record it meant. A grant that survives a rename is worth the one join the
-- address would have saved — and the grantee reads the new address out of their own listing anyway.
--
-- `role` uses the vocabulary `namespace_member.role` already has (owner | editor | viewer) rather
-- than a second pair of words for the same two ideas. `owner` is not a value here: a skill's owner is
-- its namespace's owner, which is a fact `skill.namespace_id` already carries.
--
-- There is no `revoked_at`. Revoking deletes the row; the history lives in `audit_event`, which is
-- where a question about the past belongs. A soft revocation would be a second source of truth for
-- "may this person read this", and nothing here needs to read a grant after it was withdrawn.
CREATE TABLE skill_grant (
  skill_id   TEXT NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
  grantee_id TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  role       TEXT NOT NULL,
  granted_by TEXT NOT NULL REFERENCES app_user(id),
  created_at TEXT NOT NULL,
  PRIMARY KEY (skill_id, grantee_id),
  CONSTRAINT skill_grant_role CHECK (role IN ('viewer', 'editor'))
);

-- "Which skills are granted to me" is the hot path: it is one disjunct of every listing and every
-- single-skill read. The primary key leads with `skill_id`, which answers the other direction.
CREATE INDEX idx_skill_grant_grantee ON skill_grant(grantee_id);

COMMENT ON TABLE skill_grant IS
  'A skill shared with an account (ADR 0034). Reads and writes are separate predicates over this table: viewer may read, editor may also submit versions.';
COMMENT ON COLUMN skill_grant.skill_id IS
  'The skill, by identity. Not the address: a grant must survive a rename (ADR 0004).';
COMMENT ON COLUMN skill_grant.grantee_id IS
  'The account it was shared with. Nobody grants themselves anything - the owner already has both.';
COMMENT ON COLUMN skill_grant.role IS
  'viewer | editor. The same two words namespace_member uses, minus owner.';
COMMENT ON COLUMN skill_grant.granted_by IS
  'Who shared it. Only someone who may write the skill can, so this is that person - kept for the audit row.';
COMMENT ON COLUMN skill_grant.created_at IS
  'RFC3339 UTC.';
