-- Two tag layers with different owners. v14, ADR 0083.
--
-- Public tags are the author's words attached to a skill; they are public because the skill is,
-- and they exist to be recalled. There is no definition table: the normalized word form is the
-- identity, and merging two spellings later is a data migration rather than a layer added now.
--
-- Private tags belong to one person and describe how that person files a skill in their own
-- library. They need a definition because they can be renamed and described once and seen
-- everywhere; the attachment is the "where", keyed by that stable id.
CREATE TABLE skill_public_tag (
  skill_id   TEXT NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
  tag_key    TEXT NOT NULL,
  tag_name   TEXT NOT NULL,
  created_at TEXT NOT NULL,
  PRIMARY KEY (skill_id, tag_key),
  CHECK (char_length(tag_name) BETWEEN 1 AND 24)
);

-- A word is looked up across skills by its normalized form; the primary key already answers
-- "which words does this skill carry".
CREATE INDEX idx_public_tag_key ON skill_public_tag(tag_key);

CREATE TABLE my_tag_definition (
  id          TEXT PRIMARY KEY,
  user_id     TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  name        TEXT NOT NULL,
  name_key    TEXT NOT NULL,
  description TEXT,
  created_at  TEXT NOT NULL,
  updated_at  TEXT NOT NULL,
  UNIQUE (user_id, name_key),
  CHECK (char_length(name) BETWEEN 1 AND 24),
  CHECK (description IS NULL OR char_length(description) <= 200)
);

CREATE INDEX idx_my_tag_definition_user ON my_tag_definition(user_id);

CREATE TABLE my_tag_attachment (
  definition_id TEXT NOT NULL REFERENCES my_tag_definition(id) ON DELETE CASCADE,
  skill_id      TEXT NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
  created_at    TEXT NOT NULL,
  PRIMARY KEY (definition_id, skill_id)
);

-- "Which of my tags are on this skill" reads from the skill side; the primary key starts from the
-- definition side. No user_id copy: the owner lives on the definition, so a join cannot disagree
-- with itself.
CREATE INDEX idx_my_tag_attachment_skill ON my_tag_attachment(skill_id);

COMMENT ON TABLE skill_public_tag IS
  'An author''s public word on a skill (ADR 0083). The normalized tag_key is the identity; tag_name is how that author wrote it. Public because the skill is public: anyone who can see the skill can read these.';
COMMENT ON COLUMN skill_public_tag.skill_id IS
  'The skill the word is attached to. Deleting the skill removes its public tags.';
COMMENT ON COLUMN skill_public_tag.tag_key IS
  'The normalized form: NFKC, trimmed, internal runs of whitespace collapsed to one space, lower-cased in Locale.ROOT. Always 1-24 code points after normalization.';
COMMENT ON COLUMN skill_public_tag.tag_name IS
  'The author''s own spelling, shown on the skill. A cross-skill vocabulary shows the earliest attachment''s spelling so one word does not change shape between requests.';
COMMENT ON COLUMN skill_public_tag.created_at IS
  'RFC3339 UTC. Also the tie-breaker for which spelling a vocabulary shows.';

COMMENT ON TABLE my_tag_definition IS
  'One private tag definition: a name, an optional sentence saying when to use it, and the person who owns it (ADR 0083). Renaming or describing changes every attachment at once; deleting it removes them with it.';
COMMENT ON COLUMN my_tag_definition.id IS
  'Opaque ULID. Addressing a definition (rename, describe, delete) uses this, never the name, because a name can change and two people may use the same word.';
COMMENT ON COLUMN my_tag_definition.user_id IS
  'The only person who can see or edit this definition. Deleting the account removes its definitions and, through them, every attachment.';
COMMENT ON COLUMN my_tag_definition.name IS
  'The display form, 1-24 code points. Not the identity.';
COMMENT ON COLUMN my_tag_definition.name_key IS
  'The normalized form, unique per person. Same rules as skill_public_tag.tag_key.';
COMMENT ON COLUMN my_tag_definition.description IS
  'An optional sentence (<= 200 code points) saying when to attach this tag. NULL means none; an empty submission is stored as NULL rather than an empty string.';
COMMENT ON COLUMN my_tag_definition.created_at IS
  'RFC3339 UTC.';
COMMENT ON COLUMN my_tag_definition.updated_at IS
  'RFC3339 UTC. Moves on rename and describe, not on attach or detach.';

COMMENT ON TABLE my_tag_attachment IS
  'A private tag definition attached to one skill (ADR 0083). Only the definition''s owner ever sees it, and only while the library entry is effective.';
COMMENT ON COLUMN my_tag_attachment.definition_id IS
  'The definition being attached. Its owner is the person the attachment belongs to.';
COMMENT ON COLUMN my_tag_attachment.skill_id IS
  'The skill the definition is attached to. May name a skill the owner cannot currently see: the attachment sleeps with the library entry rather than being deleted.';
COMMENT ON COLUMN my_tag_attachment.created_at IS
  'RFC3339 UTC.';
