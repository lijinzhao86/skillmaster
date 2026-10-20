-- A draft gets a required name, and `changelog` becomes a field with a write path.
-- v6, ADR 0055.
--
-- Until v6 the author's label for a version was the version number, which a draft does not have —
-- so a draft could only be pointed at by its digest. ADR 0055 gives every submission a short
-- human name (`draft_name`) that survives publishing as the author-plane label, while the version
-- number stays the thing callers address.
--
-- `draft_name` is nullable on purpose. Rows submitted before v6 have no name and inventing one for
-- them would be a fabricated identity; the author plane falls back to the version number. New
-- submissions are required to carry one by the application layer, not by a NOT NULL that could not
-- be applied to the existing rows.
--
-- `changelog` already exists; this migration only corrects what its comment promises. It was
-- written as "v1 has no endpoint that sets it", which stopped being true in v6: the note is written
-- at submission and on a draft, and frozen at publish (ADR 0058's state guard).
ALTER TABLE skill_version ADD COLUMN draft_name TEXT;

COMMENT ON COLUMN skill_version.draft_name IS
  'The author-plane label for this version (1-40 code points, required on new submissions). NULL on rows submitted before v6. Not part of the digest and not an address: the draft''s identity is draft:<id> and the published version''s is the version number (ADR 0055).';
COMMENT ON COLUMN skill_version.changelog IS
  'The one-line note the author gave this version. Written at submission, editable while the row is a draft, frozen once published. Not part of the digest.';

-- The note is one line by contract; older rows are all empty, so this cannot fail on data. The
-- check is in characters, matching the API's code-point limit, not in bytes.
ALTER TABLE skill_version ADD CONSTRAINT skill_version_changelog_length
  CHECK (char_length(changelog) <= 200);
