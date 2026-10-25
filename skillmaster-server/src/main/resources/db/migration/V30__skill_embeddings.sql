-- Semantic retrieval for skills (v19). One table: the vectors themselves.
--
-- **One vector per skill, not per version.** What gets embedded is the text search already reads:
-- `skill.name`, `title`, `description`, `when_to_use`. Those are a projection of the published
-- version, written onto the skill row when the pointer moves (V21, ADR 0031) — a draft's text is
-- not on that row and is not searchable. So there is never a second candidate text for one skill,
-- and a `version_id` in the key would be a dimension with one value.
--
-- **No state column either.** Whether a skill is live, draft, offline or deleted stays in M12.
-- Search asks M12 afterwards which of the candidates the caller may open, so a lifecycle change
-- needs no vector work at all: there is no copy of the state to keep in step. Deleting a skill
-- cascades this row away.
--
-- **`model` is part of the key** so that changing embedding models is additive: embed with the new
-- name, switch reads over, delete the old rows. Without it a model change would be an UPDATE across
-- the whole table with no way to tell an old vector from a new one.
--
-- **No outbox table, and that is a decision** (2026-10-25): the vector is computed inside the
-- publishing transaction, so a publish that commits has its vector and one that fails does not
-- happen. The same shape the SMS path already uses for its own external call. The price is that
-- publishing waits on the provider and fails when it is down; `text_hash` is what still keeps a
-- result from being written against a text it was not computed from.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE skill_embedding (
    skill_id   TEXT NOT NULL REFERENCES skill (id) ON DELETE CASCADE,
    model      TEXT NOT NULL,
    text_hash  TEXT NOT NULL,
    embedding  vector(1024) NOT NULL,
    -- TEXT, RFC3339 UTC, like every other timestamp in this schema (V1): the application writes
    -- the string it already has, and a TIMESTAMPTZ here would have to be parsed on the way in.
    updated_at TEXT NOT NULL,
    PRIMARY KEY (skill_id, model)
);

COMMENT ON TABLE skill_embedding IS
  'One vector per skill per model (v19). No version and no lifecycle state: the embedded text is '
  'the published projection on the skill row, and visibility is asked of M12 when searching. '
  'Written in the publishing transaction, so a published skill always has one. Cascades with the '
  'skill.';
