-- Permissions become a ladder: five grantable rungs, each including everything below it.
-- v5, ADR 0047.
--
-- The vocabulary is replaced, not extended: `viewer` and `editor` stop being valid values, so the
-- existing rows are backfilled by meaning — `viewer` could read, which is `browse`; `editor` could
-- also submit versions, which is `write`. Mapping the old words silently at the edge would be worse
-- than refusing them: a request that says "viewer" and is answered "granted" would have granted
-- something the caller did not ask for, so the interface rejects them with `400 invalid_request`.
--
-- `owner` is still not a value here, for V12's reason unchanged: a skill's owner is its namespace's
-- owner, which `skill.namespace_id` already says. The ladder draws the owner above all five, but it
-- is a relationship, not a grantable value.
--
-- The order is not stored. It is a constant in the code (`GrantRole`), and this column carries a
-- rung's name exactly as it carried `viewer` before.
ALTER TABLE skill_grant DROP CONSTRAINT skill_grant_role;
UPDATE skill_grant SET role = 'browse' WHERE role = 'viewer';
UPDATE skill_grant SET role = 'write'  WHERE role = 'editor';
ALTER TABLE skill_grant ADD CONSTRAINT skill_grant_role
  CHECK (role IN ('invoke', 'browse', 'write', 'maintain', 'admin'));

-- V12's table and column comments still say viewer / editor. Correct them in the same migration —
-- the same housekeeping V19 does for the comments ADR 0037 left stale.
COMMENT ON TABLE skill_grant IS
  'A skill shared with an account (ADR 0034/0047). The role is one rung of the ladder, and each rung includes everything below it.';
COMMENT ON COLUMN skill_grant.role IS
  'invoke | browse | write | maintain | admin. Five grantable rungs (ADR 0047); the owner is not in this table.';

-- No new index: the primary key answers (skill, grantee), and nothing looks a grant up by role.
