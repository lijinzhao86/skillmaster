-- v39: bytes above one mebibyte live in the private OSS bucket (ADR 0130), so `blob` has to say
-- where each digest's bytes are. The decision is a function of size alone, never of content type:
-- a storage location that depended on a UTF-8 probe could put the same bytes in two places.
--
-- Existing rows are all 'db'. They are not migrated; the read path consults this column and does
-- not care which answer it gets.

ALTER TABLE blob ADD COLUMN storage TEXT NOT NULL DEFAULT 'db';
ALTER TABLE blob ADD COLUMN location TEXT;

ALTER TABLE blob
  ADD CONSTRAINT blob_storage_known
    CHECK (storage IN ('db', 'oss')),
  ADD CONSTRAINT blob_location_matches_storage
    CHECK ((storage = 'db' AND location IS NULL)
        OR (storage = 'oss' AND location IS NOT NULL));

COMMENT ON COLUMN blob.storage IS
  'Bytes live either in blob_content (db) or in the private OSS bucket (oss). The choice is M6''s; no caller may name it.';
COMMENT ON COLUMN blob.location IS
  'The OSS object key, always blobs/<sha256>. Stored rather than recomputed so a future key scheme can change without moving every object.';

-- OSS deletion cannot join a database transaction. The rows below are written in the same
-- transaction that removes a blob row, and a later pass deletes the object and then the row.
-- At-least-once: a crash leaves the row for the next pass, and deleting an absent object is a
-- no-op.
CREATE TABLE blob_deletion (
  sha256     TEXT PRIMARY KEY,
  location   TEXT NOT NULL,
  created_at TEXT NOT NULL
);

COMMENT ON TABLE blob_deletion IS
  'An outbox for OSS objects whose blob row is gone. Drained after the deleting transaction commits; failures stay for the next pass.';
COMMENT ON COLUMN blob_deletion.created_at IS
  'RFC3339 UTC.';
