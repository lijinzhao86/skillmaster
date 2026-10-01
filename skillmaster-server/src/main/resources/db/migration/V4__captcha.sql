-- The captcha challenge (M1): the image a person has to read before an SMS is sent.
--
-- A second migration rather than an addition to V3, which was written in the same round and is not
-- committed yet. V3 has already been applied wherever this branch has been run, and Flyway refuses
-- to start when an applied migration's checksum changes — so folding this in would mean rebuilding
-- every database that has one, including a developer's. A version number costs nothing; a rebuild
-- costs somebody's afternoon. Same reasoning as V3's own header.

CREATE TABLE captcha (
  id          TEXT PRIMARY KEY,
  answer_hash TEXT NOT NULL,
  attempts    INTEGER NOT NULL DEFAULT 0,
  expires_at  TEXT NOT NULL,
  consumed_at TEXT,
  created_at  TEXT NOT NULL
);

COMMENT ON TABLE captcha IS
  'An issued captcha challenge. ADR 0013 names a captcha as the third of the three anti-abuse measures and the only one that notices an attacker with many addresses; it guards the two endpoints that send an SMS, because those are the ones that spend money.';
COMMENT ON COLUMN captcha.id IS
  'ULID, and the handle the client sends back with its answer. The answer itself is never in the response.';
COMMENT ON COLUMN captcha.answer_hash IS
  'sha256(upper(answer)) as hex. **Not the defence**: four characters from a 32-character alphabet are about a million values and enumerate offline in seconds, so a leaked table is a list of live answers. What bounds an attacker is the five-minute life, the three attempts and the issuance rate limit — the same reasoning as phone_verification.code_hash.';
COMMENT ON COLUMN captcha.attempts IS
  'Wrong answers so far. The row stops being usable once this passes the cap — four characters are worth a few tries, not unlimited ones.';
COMMENT ON COLUMN captcha.expires_at IS
  'RFC3339 UTC. Short: an unanswered captcha is a row nobody will ever consume.';
COMMENT ON COLUMN captcha.consumed_at IS
  'RFC3339 UTC, or NULL while the challenge is still usable. Set when it is accepted, which is what makes it single-use.';
COMMENT ON COLUMN captcha.created_at IS 'RFC3339 UTC.';

-- No index beyond the primary key, unlike phone_verification: this table is looked up by the id the
-- client was handed, never by a value the caller supplies.
