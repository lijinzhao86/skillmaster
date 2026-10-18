-- A request for a rung is its own object, not a grant. v5, ADR 0052.
--
-- The primary key is (skill, requester): one account has at most one pending request per skill, and
-- resubmitting is the same intent expressed again — an upsert over that row, not a second record.
-- Nothing in the access predicates reads this table. Approving writes `skill_grant` through the
-- same code path a direct grant uses, so the ladder, the audit and the per-rung filtering never
-- learn the word "request".
--
-- It follows the skill on hard delete (`ON DELETE CASCADE`) and survives soft delete: a soft delete
-- only writes `skill.deleted_at`, so restoring the skill brings its pending requests back with it.
CREATE TABLE skill_access_request (
  skill_id     TEXT NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
  requester_id TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  wanted_role  TEXT NOT NULL,
  created_at   TEXT NOT NULL,
  PRIMARY KEY (skill_id, requester_id),
  CONSTRAINT skill_access_request_role
    CHECK (wanted_role IN ('invoke', 'browse', 'write', 'maintain', 'admin'))
);

-- No index, and that is the opposite of `skill_grant`'s reason rather than a contradiction: that
-- table has a hot "which skills are granted to me" path, this one has no "what did I request"
-- screen. The admin's list is by `skill_id`, which the primary key already covers.

COMMENT ON TABLE skill_access_request IS
  'One account asking for one rung on one skill (ADR 0052). Grants nothing: approval writes skill_grant.';
COMMENT ON COLUMN skill_access_request.wanted_role IS
  'The rung the requester asked for. The approver may grant a different one; that rung lives in skill_grant.';
COMMENT ON COLUMN skill_access_request.created_at IS
  'RFC3339 UTC, moved when the requester resubmits (resubmitting is an upsert, not a second row).';
