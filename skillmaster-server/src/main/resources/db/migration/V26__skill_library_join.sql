-- A reference from a person's library to somebody else's public skill. v14, ADR 0082.
--
-- A library entry is derived, not stored: "mine" comes from the namespace, "shared with me" from
-- `skill_grant`, and this table holds the one source that has nowhere else to live — skills the
-- person chose to keep. Copying the other two in would give the same fact two homes.
--
-- There is no source column and no state column. Both are computed every time (see the predicate
-- in `SkillPredicates`): a stored copy would drift the moment a grant is issued or revoked, and a
-- stored "active" flag would need a backfill job for a fact the query already knows. Leaving is a
-- DELETE; there is no tombstone because re-joining is a fresh row with a fresh time.
CREATE TABLE skill_library_join (
  user_id    TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  skill_id   TEXT NOT NULL REFERENCES skill(id)    ON DELETE CASCADE,
  created_at TEXT NOT NULL,
  PRIMARY KEY (user_id, skill_id)
);

-- The primary key covers "what is in my library", which every library listing starts from. This
-- one covers the other direction: a skill's removal cascades, and the library predicate asks
-- whether one particular skill is in the caller's library.
CREATE INDEX idx_library_join_skill ON skill_library_join(skill_id);

COMMENT ON TABLE skill_library_join IS
  'One row per skill a person added to their library (ADR 0082). An entry is a reference, not a copy: no version, no bytes, no access are moved. Only the "joined" half is stored; "created" and "shared" are derived from skill and skill_grant.';
COMMENT ON COLUMN skill_library_join.user_id IS
  'The person whose library holds the entry. Deleting the account removes its library.';
COMMENT ON COLUMN skill_library_join.skill_id IS
  'The skill being referenced. Deleting the skill removes every reference to it.';
COMMENT ON COLUMN skill_library_join.created_at IS
  'RFC3339 UTC. When the person joined. Display-only; the library list orders by the skill''s own update time, not this one.';
