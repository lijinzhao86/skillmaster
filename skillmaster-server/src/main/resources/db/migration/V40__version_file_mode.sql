-- The unix permission bits of one file of a version (v51).
--
-- Only the executable bit is acted on today; the whole mode is stored because a later rule
-- (0600 for a credential-shaped file, say) would otherwise need another column.
--
-- **It is metadata, not content.** The version digest is sha256 over (relpath, blob_sha256) and
-- deliberately does not include this column: adding it there would recompute every existing
-- version's digest, and an address would stop resolving. Stored as decimal, like is_binary.
--
-- 420 is 0644. Every row that existed before this migration keeps that mode, which is what the
-- download path already wrote.
ALTER TABLE version_file ADD COLUMN mode INTEGER NOT NULL DEFAULT 420;

COMMENT ON COLUMN version_file.mode IS
  'Unix permission bits as a decimal integer (420 = 0644). Metadata: never part of the digest.';
