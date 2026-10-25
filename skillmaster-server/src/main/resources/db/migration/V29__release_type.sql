-- How big this change is, declared by the author. ADR 0091.
--
-- **A declaration, not a number.** The version number is still computed at publish time from this
-- and the current live version; nothing here is pre-allocated, which is what keeps the column from
-- becoming the "draft burns a number" shape ADR 0055 rejected. The column therefore carries no
-- identity: it is not in the digest, it is not in the address, and it can change for as long as the
-- row is a draft.
--
-- **Nullable, and NULL means undeclared — nothing inherits.** A second submission without a type
-- gets NULL even if the previous draft declared one; the type is a judgement about *this* change,
-- and last time's judgement is not evidence for this time's. Rows that predate this column have no
-- type either, which is the same situation: publish them with an explicit number, or declare a type
-- first.
--
-- **No index.** Nothing queries by it: the queue read carries it out per row for display, and the
-- publish path only ever reads the one row it is publishing.
--
-- **CHECK rather than an enum type.** A PostgreSQL enum would need a migration to extend if a fourth
-- level ever appears, and it would make the column a second place that knows the three names. The
-- names themselves live in Java (`modules/version/ReleaseType`) for the same reason `Scopes` does.
ALTER TABLE skill_version ADD COLUMN release_type TEXT;

ALTER TABLE skill_version ADD CONSTRAINT skill_version_release_type
  CHECK (release_type IS NULL OR release_type IN ('major', 'minor', 'patch'));

COMMENT ON COLUMN skill_version.release_type IS
  'How big this change is (major/minor/patch), declared by the author. It is not a version number: '
  'the number is computed at publish time from this and the current live version (ADR 0091). NULL '
  'means undeclared, and nothing is inherited.';
