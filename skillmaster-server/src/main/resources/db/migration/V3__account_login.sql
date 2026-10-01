-- Account login (M1): the phone columns, the verification-code and throttle tables, and the
-- session store that replaces `browser_session`.
--
-- A new migration rather than an edit to V1. V1 has been applied wherever it has been applied at
-- all, and Flyway refuses to start when an applied migration's checksum no longer matches — so
-- rewriting it would mean rebuilding every database that has one, for no benefit. Adding a version
-- costs nothing anywhere.
--
-- Every table and column carries a COMMENT, as in V1; those comments are the only place a column's
-- meaning is written down here.

-- ---------------------------------------------------------------------------
-- 3.1a app_user: the phone, as a blind index plus a ciphertext
-- ---------------------------------------------------------------------------

-- Nullable, exactly like `email`: the three users seeded by V2 have no phone and are not expected
-- to log in. PostgreSQL's UNIQUE permits any number of NULLs, so "one phone is one account" holds
-- without forcing a value onto rows that predate the column.
ALTER TABLE app_user ADD COLUMN phone_hash TEXT UNIQUE;
ALTER TABLE app_user ADD COLUMN phone_enc  TEXT;

-- Both columns are written by the registration path or neither is. A row with one and not the other
-- is a state no code path produces, so no reading code has to decide what it would mean.
ALTER TABLE app_user ADD CONSTRAINT app_user_phone_paired
  CHECK ((phone_hash IS NULL) = (phone_enc IS NULL));

-- The V1 comment on this column says "Login name", which ADR 0013 made false: the login identifier
-- is the phone. Restated rather than left to mislead, since the comment is the only place the
-- column's meaning is written down.
COMMENT ON COLUMN app_user.handle IS
  'Public username: chosen at registration, immutable, and the slug of this user''s personal namespace (3.2). NOT the login identifier — that is the phone number (ADR 0013), which appears in no URL. UNIQUE, which is what keeps a reserved name like ''skillmaster'' out of reach.';
COMMENT ON COLUMN app_user.phone_hash IS
  'HMAC-SHA256(phone) as hex, keyed by skillmaster.account.phone-hmac-key. The blind index login looks up by; the number itself is not here. UNIQUE, so one phone is one account.';
COMMENT ON COLUMN app_user.phone_enc IS
  'AES-GCM(phone): base64 of iv || ciphertext || tag, keyed by skillmaster.account.phone-enc-key. Decrypted only to show a number back to its owner. Paired with phone_hash by app_user_phone_paired.';

-- ---------------------------------------------------------------------------
-- 3.1b phone_verification: one SMS code, for registration and reset alike
-- ---------------------------------------------------------------------------

CREATE TABLE phone_verification (
  id          TEXT PRIMARY KEY,
  phone_hash  TEXT NOT NULL,
  purpose     TEXT NOT NULL,
  code_hash   TEXT NOT NULL,
  attempts    INTEGER NOT NULL DEFAULT 0,
  expires_at  TEXT NOT NULL,
  consumed_at TEXT,
  created_at  TEXT NOT NULL
);
CREATE INDEX idx_pv_lookup ON phone_verification(phone_hash, purpose, created_at);

COMMENT ON TABLE phone_verification IS
  'A sent SMS verification code. Short-lived by construction: the code is never stored, only a hash of it, and the expiry plus the attempt cap are what actually bound an attacker — six digits are enumerable offline, so the hash is not the defence.';
COMMENT ON COLUMN phone_verification.id IS 'ULID.';
COMMENT ON COLUMN phone_verification.phone_hash IS
  'The same keyed hash as app_user.phone_hash. Deliberately not a foreign key: at registration there is no app_user row for it to point at.';
COMMENT ON COLUMN phone_verification.purpose IS
  'register | reset. Two flows in one table, so a code issued for one is never accepted by the other.';
COMMENT ON COLUMN phone_verification.code_hash IS
  'sha256(phone || code) as hex. See the table comment for why this is not the only defence.';
COMMENT ON COLUMN phone_verification.attempts IS
  'Wrong guesses so far. The row stops being usable once this passes the cap.';
COMMENT ON COLUMN phone_verification.expires_at IS 'RFC3339 UTC.';
COMMENT ON COLUMN phone_verification.consumed_at IS
  'RFC3339 UTC, or NULL while the code is still usable. Set when it is accepted, which is what makes it single-use.';
COMMENT ON COLUMN phone_verification.created_at IS 'RFC3339 UTC.';

-- ---------------------------------------------------------------------------
-- 3.1c auth_throttle: the rate-limit counters
-- ---------------------------------------------------------------------------

CREATE TABLE auth_throttle (
  scope        TEXT    NOT NULL,
  key_hash     TEXT    NOT NULL,
  window_start TEXT    NOT NULL,
  attempts     INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (scope, key_hash, window_start)
);

COMMENT ON TABLE auth_throttle IS
  'Fixed-window counters for every rate-limit rule M1 has. One table rather than a counting query per rule: sending an SMS costs money per message, and a login endpoint without a counter is an online password oracle. Rows past the longest window are swept opportunistically on each write.';
COMMENT ON COLUMN auth_throttle.scope IS
  'Which rule this row counts: sms:cooldown | sms:daily | sms:ip | login:phone | login:ip. The window length and the cap live beside it in code, because a rule is one thing.';
COMMENT ON COLUMN auth_throttle.key_hash IS
  'What is being limited, hashed: the keyed phone hash for the phone rules, a plain sha256 of the address for the IP rules. Not the value itself — a table of raw client addresses is a privacy liability with no upside.';
COMMENT ON COLUMN auth_throttle.window_start IS
  'RFC3339 UTC, truncated to the start of the window this row counts. Text like every other time in this schema.';
COMMENT ON COLUMN auth_throttle.attempts IS
  'Events seen in this window. The caller compares it against the cap.';

-- ---------------------------------------------------------------------------
-- 3.1d The session store, replacing browser_session
-- ---------------------------------------------------------------------------

-- browser_session was designed as a domain table before Spring Session was chosen. Keeping both
-- would mean two sources of truth for one cookie, and the hand-rolled one would be the one without
-- an expiry sweep, without rotation on login, and without concurrency control. Dropped rather than
-- left unused, because an unused table is a table somebody later writes to by mistake.
DROP TABLE browser_session;

-- Reproduced verbatim from spring-session-jdbc 4.1.1, schema-postgresql.sql — except that the
-- identifiers are lower case. PostgreSQL folds Spring Session's own unquoted upper-case names to
-- exactly these, so its statements resolve unchanged.
--
-- Declared here rather than left to Boot's script initializer (spring.session.jdbc.initialize-schema
-- is `never`) so that Flyway remains the only thing that builds this schema. That also makes these
-- tables M1's, which is what TableOwnershipTest requires of anything a migration creates.
--
-- A later Spring Session version changing this schema is not detected by anything here: this copy
-- is what would have to move with it.
CREATE TABLE spring_session (
  primary_id            CHAR(36) NOT NULL,
  session_id            CHAR(36) NOT NULL,
  creation_time         BIGINT   NOT NULL,
  last_access_time      BIGINT   NOT NULL,
  max_inactive_interval INT      NOT NULL,
  expiry_time           BIGINT   NOT NULL,
  principal_name        VARCHAR(100),
  CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);

CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
  session_primary_id CHAR(36)     NOT NULL,
  attribute_name     VARCHAR(200) NOT NULL,
  attribute_bytes    BYTEA        NOT NULL,
  CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
  CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
      REFERENCES spring_session (primary_id) ON DELETE CASCADE
);

COMMENT ON TABLE spring_session IS
  'Spring Session''s session rows. Owned by M1, whose login establishes them; the framework reads and writes them, so nothing here may spell these table names in SQL.';
COMMENT ON COLUMN spring_session.primary_id IS 'The session''s own id, as used inside Spring Session.';
COMMENT ON COLUMN spring_session.session_id IS 'The id as carried in the cookie.';
COMMENT ON COLUMN spring_session.creation_time IS 'Epoch milliseconds — the framework''s format, not this schema''s RFC3339 convention.';
COMMENT ON COLUMN spring_session.last_access_time IS 'Epoch milliseconds.';
COMMENT ON COLUMN spring_session.max_inactive_interval IS 'Idle timeout in seconds.';
COMMENT ON COLUMN spring_session.expiry_time IS 'Epoch milliseconds; the sweep compares against it.';
COMMENT ON COLUMN spring_session.principal_name IS
  'The authenticated principal''s name, taken from the SecurityContext attribute. M1 puts the user id here, which is what makes "revoke every session for this user" a lookup rather than a scan.';
COMMENT ON TABLE spring_session_attributes IS
  'Session attribute values, one row each — including the serialized SecurityContext.';
COMMENT ON COLUMN spring_session_attributes.session_primary_id IS 'The spring_session row this attribute belongs to.';
COMMENT ON COLUMN spring_session_attributes.attribute_name IS 'The attribute''s name.';
COMMENT ON COLUMN spring_session_attributes.attribute_bytes IS 'The attribute''s serialized value.';
