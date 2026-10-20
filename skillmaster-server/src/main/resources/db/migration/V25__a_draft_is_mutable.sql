-- v7 (ADR 0064): a draft's content changes; a published version's does not.
--
-- Comment-only: the earlier comments on these objects were written when no operation could change a
-- row's file set, and they now describe the opposite of what the code does. PostgreSQL keeps one
-- comment per object, so this migration replaces them; no data, index or constraint moves.

COMMENT ON TABLE skill_version IS
  'One version: a skill''s Nth distinct file set, with who submitted it and when, plus a state that changes. A draft is mutable (digest, file_count, total_bytes and version_file move with each file write); once published, the content is frozen (ADR 0064). State is not part of the digest and does not move an address.';

COMMENT ON COLUMN skill_version.id IS
  'The row id: eight Crockford-base32 characters on rows v7 creates, or the 26-character ULID of an older row (ADR 0063). Addressed bare on the author plane; not a consumption-plane address.';

COMMENT ON COLUMN skill_version.digest IS
  'The manifest digest (ADR 0005). UNIQUE per skill: the same content cannot be two versions of one skill. A draft write recomputes it; a published row keeps it forever.';
