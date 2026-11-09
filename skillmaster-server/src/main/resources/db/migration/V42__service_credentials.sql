-- v52: service credentials (ADR 0168–0170).
--
-- **Two objects, not one.** `service_credential` is a value that belongs to a person;
-- `credential_binding` is what exposes it to one skill and one environment variable. The same key
-- used by two skills is one row in the first table and two in the second — rotating it changes both
-- skills at once, and a skill nobody bound it to can never read it.
--
-- **No plaintext column and no key version.** `ciphertext` is the blob the cloud KMS returned,
-- base64 so the column stays TEXT like everything else here; the blob carries the CMK and its
-- version, so a KMS rotation needs no migration. The only place it is decrypted is the lease
-- endpoint, and that response is `no-store` and never logged.

CREATE TABLE service_credential (
  id            TEXT PRIMARY KEY,
  owner_user_id TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  name          TEXT NOT NULL,
  ciphertext    TEXT NOT NULL,
  byte_len      INTEGER NOT NULL,
  per_day_limit INTEGER NOT NULL DEFAULT 500,
  created_at    TEXT NOT NULL,
  rotated_at    TEXT,
  last_used_at  TEXT,
  CONSTRAINT service_credential_owner_name UNIQUE (owner_user_id, name),
  CONSTRAINT service_credential_limit_range CHECK (per_day_limit BETWEEN 1 AND 10000)
);

COMMENT ON TABLE service_credential IS
  'A value a person asked us to keep. Never read back: there is no endpoint that returns this column decrypted except the lease.';
COMMENT ON COLUMN service_credential.ciphertext IS
  'Base64 of the cloud-KMS blob. The blob carries the CMK and its version, so rotation needs no column here.';
COMMENT ON COLUMN service_credential.per_day_limit IS
  'Leases per day, counted per credential (not per binding). 500 by default; the console and `skmr secret quota` change it.';

-- One row per (credential, skill, environment variable). This table **is** the exposure surface:
-- injection resolves bindings, never credentials.
CREATE TABLE credential_binding (
  id            TEXT PRIMARY KEY,
  credential_id TEXT NOT NULL REFERENCES service_credential(id) ON DELETE CASCADE,
  -- Denormalised from the credential on purpose: it is what makes "one owner, one binding per
  -- (skill, variable)" a **database** rule rather than a check two concurrent requests can both
  -- pass. The service layer still checks first, so the ordinary path answers with a sentence
  -- instead of a constraint violation.
  owner_user_id TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  skill_id      TEXT NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
  env_name      TEXT NOT NULL,
  created_at    TEXT NOT NULL,
  CONSTRAINT credential_binding_owner_unique UNIQUE (owner_user_id, skill_id, env_name)
);

CREATE INDEX idx_credential_binding_skill ON credential_binding (skill_id);

COMMENT ON TABLE credential_binding IS
  'What makes a value reachable: (credential, skill, env name). A skill with no row here never receives the value; a collaborator cannot create one for somebody else''s credential.';

-- Daily aggregate, written by the lease. Deliberately not one audit row per use: a busy skill
-- would bury the audit table under its own traffic.
CREATE TABLE credential_use (
  credential_id TEXT NOT NULL REFERENCES service_credential(id) ON DELETE CASCADE,
  day           TEXT NOT NULL,
  count         INTEGER NOT NULL,
  last_used_at  TEXT NOT NULL,
  PRIMARY KEY (credential_id, day)
);

COMMENT ON TABLE credential_use IS
  'Lease counts per credential per day (YYYY-MM-DD). Quota reads this row; audit keeps the four lifecycle events instead of one line per use.';

-- Sharing is per (credential, skill) — the requirement's "this credential, for this skill".
--
-- **The row names the credential and the skill, not one binding.** A share written while the
-- credential had one variable must keep covering the variable somebody binds next week — and a
-- binding removed later must not silently revoke the grant. Keying it on a binding got both of
-- those wrong. A revoked row stays as history; the partial unique index guards only the live grant.
CREATE TABLE credential_share (
  id              TEXT PRIMARY KEY,
  credential_id   TEXT NOT NULL REFERENCES service_credential(id) ON DELETE CASCADE,
  skill_id        TEXT NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
  grantee_user_id TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  granted_by      TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  created_at      TEXT NOT NULL,
  revoked_at      TEXT
);

CREATE UNIQUE INDEX uq_credential_share_active
  ON credential_share (credential_id, skill_id, grantee_user_id)
  WHERE revoked_at IS NULL;

CREATE INDEX idx_credential_share_grantee ON credential_share (grantee_user_id);

COMMENT ON TABLE credential_share IS
  'This person may receive this credential on this skill — every variable it is bound to there, now or later. The lease and the shares listing both resolve it at this level.';
