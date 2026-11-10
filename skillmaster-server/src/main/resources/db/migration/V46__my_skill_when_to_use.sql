-- v59: the caller's own "when I would look for this" sentence.
--
-- **Keyed by person x skill, not by the library entry.** The library is derived from three sources
-- (created, shared, joined), so no single row could carry a foreign key to it; and the sentence is
-- kept when the skill leaves the library or a share is withdrawn (2026-11-11 decision). Deleting an
-- account or hard-deleting a skill removes it through the two cascades below.
--
-- **One row per pair.** Writing replaces the sentence, clearing removes the row; there is no history
-- and no version. The text is private to its author: no other reader joins this table.
CREATE TABLE my_skill_when_to_use (
  user_id     TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  skill_id    TEXT NOT NULL REFERENCES skill(id)    ON DELETE CASCADE,
  when_to_use  TEXT NOT NULL,
  updated_at   TEXT NOT NULL,
  PRIMARY KEY (user_id, skill_id),
  CHECK (char_length(when_to_use) BETWEEN 1 AND 200)
);

-- A hard delete of the skill cascades from the skill side; the primary key starts from the person.
CREATE INDEX idx_my_skill_when_to_use_skill ON my_skill_when_to_use(skill_id);

COMMENT ON TABLE my_skill_when_to_use IS
  'The caller''s own sentence for when they would look for a skill in their library (v59). Private: only its author reads it, and it never changes the skill, its versions or its public card.';
COMMENT ON COLUMN my_skill_when_to_use.user_id IS
  'The person who wrote the sentence. Per-account and never shared.';
COMMENT ON COLUMN my_skill_when_to_use.skill_id IS
  'The skill the sentence is about. Kept when the skill leaves the caller''s library; removed only when the skill itself is hard-deleted.';
COMMENT ON COLUMN my_skill_when_to_use.when_to_use IS
  'Plain text, 1-200 Unicode code points after trimming and collapsing internal whitespace. The CHECK is a backstop; the service is what counts code points.';
COMMENT ON COLUMN my_skill_when_to_use.updated_at IS
  'RFC3339 UTC. Moves only when the sentence itself changes, not on every write of the same text.';
