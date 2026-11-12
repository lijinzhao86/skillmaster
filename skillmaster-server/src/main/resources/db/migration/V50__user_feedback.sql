-- v64: one feedback document a person sends the platform.
--
-- **The row is the record; the object storage copy is a durability copy.** Nothing is deleted on
-- a schedule: the privacy policy says the content is kept for as long as it is needed, and the
-- archive job writes every row to the bucket without removing it from here. A withdrawal deletes
-- both, which is why the archive key is on the row rather than derived at read time.
--
-- **The account row survives a purge** (v56 de-identifies `app_user` instead of deleting it), so
-- the foreign key never fires on account deletion. The feedback stays, pointing at an account
-- whose handle now reads `deleted-<id>`.
CREATE TABLE user_feedback (
  id              TEXT PRIMARY KEY,
  receipt         TEXT NOT NULL UNIQUE,
  user_id         TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  skill_address   TEXT,
  content         TEXT NOT NULL,
  content_sha256  TEXT NOT NULL,
  consent_version TEXT NOT NULL,
  consent_at      TEXT NOT NULL,
  source          TEXT NOT NULL,
  diagnostics     JSONB,
  archive_key     TEXT,
  archived_at     TEXT,
  created_at      TEXT NOT NULL
);

CREATE INDEX idx_user_feedback_user_time ON user_feedback(user_id, created_at);

-- The operator's other read: a time range over everybody's rows. The per-user index cannot serve
-- it, because its leading column is unconstrained.
CREATE INDEX idx_user_feedback_created ON user_feedback(created_at);

-- The archive job selects exactly the rows that have no copy yet; a partial index keeps that
-- sweep from growing with the table. The query must use the same predicate (`archived_at IS
-- NULL`), or PostgreSQL will not consider this index.
CREATE INDEX idx_user_feedback_pending_archive ON user_feedback(archived_at)
  WHERE archived_at IS NULL;

COMMENT ON TABLE user_feedback IS
  'A feedback document a person sent the platform (v64). Kept for as long as the privacy policy says; the object storage copy is durability, not the only copy.';
COMMENT ON COLUMN user_feedback.id IS
  'ULID. Internal identity; the receipt is the value a person sees and types.';
COMMENT ON COLUMN user_feedback.receipt IS
  'FB-YYYYMMDD-XXXXXX. What the CLI prints, the notification carries and the delete command takes.';
COMMENT ON COLUMN user_feedback.user_id IS
  'Who submitted it. The account row is de-identified rather than deleted by the v56 purge, so this reference survives.';
COMMENT ON COLUMN user_feedback.skill_address IS
  'Optional context: the address the person was using, never resolved or joined.';
COMMENT ON COLUMN user_feedback.content IS
  'The document as submitted, after secret-bearing prefixes were replaced with [已隐藏]. No other redaction is applied: the phone, ID and bank patterns are warned about, not rewritten.';
COMMENT ON COLUMN user_feedback.content_sha256 IS
  'Lowercase SHA-256 of content, computed by the server. The consent record points at what was actually stored.';
COMMENT ON COLUMN user_feedback.consent_version IS
  'The privacy policy version served when the submission was accepted. Never a value the client supplies.';
COMMENT ON COLUMN user_feedback.consent_at IS
  'RFC3339 UTC. The moment the server accepted the consent.';
COMMENT ON COLUMN user_feedback.source IS
  'The channel the submission arrived on. `cli` in v64, the only channel; stored rather than implied so a future channel is distinguishable in the consent record.';
COMMENT ON COLUMN user_feedback.diagnostics IS
  'Only the whitelisted keys (cli_version, os, arch, at, command, error_code). Unknown keys are dropped before the write.';
COMMENT ON COLUMN user_feedback.archive_key IS
  'Object key in the private bucket, feedback/YYYY/MM/DD/<receipt>.ndjson.gz; null until the archive job has written it.';
COMMENT ON COLUMN user_feedback.archived_at IS
  'RFC3339 UTC. Non-null once the object exists; a withdrawal deletes the object and then the row.';
COMMENT ON COLUMN user_feedback.created_at IS
  'RFC3339 UTC.';
