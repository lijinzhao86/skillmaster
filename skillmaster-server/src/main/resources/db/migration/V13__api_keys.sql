-- The first-party credential: a key the account issues to itself from the console and pastes into a
-- CLI. ADR 0038.
--
-- **A separate table from `access_token`, and the reason is not tidiness.** That table's rows are
-- children of `oauth_authorization` — the validator's liveness check is an EXISTS over that parent,
-- and every row there is written by the framework through TokenStore. A key has no authorization
-- code, no consent and no refresh chain, so a row of this shape cannot live there without making that
-- join conditionally false. Two credentials with different lifecycles get two tables, and the seam
-- above them (M3's `TokenValidator`) does not care which one it is looking at.
--
-- **The prefix `skm_` is load-bearing twice.** It is what a secret scanner needs in order to
-- recognise a leaked key, and it is also how the validator routes without a second lookup: an OAuth
-- **access** token is a UUID string (36 characters of hex and dashes) and a refresh token is 96 bytes
-- of base64url, and neither can start with it — so the two can never be confused. (This said "an OAuth
-- token is 96 bytes of base64url" until 2026-10-15, which describes the refresh token alone.)
-- **Routing only** — the check that decides is still the hash lookup, so a wrong route is a miss
-- rather than an acceptance. The prefix lives in one place in Java (`modules/token/ApiKeyFormat`)
-- for the same reason `Scopes` does: two copies of a wire fact is one copy that will drift.
--
-- **`expires_at` is nullable and NULL means "never".** The expiry is a deployment setting: with it
-- on, issuance fills in a default — 90 days, and ADR 0039 §决定 1 is where that number and its
-- limits live. (ADR 0038 §决定 3 had it at 365, following the reference product; ADR 0039 §理由
-- records why not — that product's 365 is carried by a 60/30/7-day reminder chain we do not have,
-- so a year would mean every machine failing silently on the same day.) With the switch off, a key
-- may be issued with no expiry at all. The column therefore has to be able to say both things, and
-- `IS NULL` rather than a sentinel timestamp is the spelling that cannot be misread.
--
-- **No `audience` column, unlike `access_token`.** There it answers an RFC 8707 question the
-- protocol asks. A key is minted here, stored here and presented here, so "minted for this service"
-- is not a property it can fail to have.
--
-- **No `created_by` column.** In v1 the issuer and the subject are the same account — ADR 0038
-- defers organisation-owned keys to the organisation model, where a key would belong to a synthetic
-- user instead. "Who created this" is a question about the past, and the past is `audit_event`; V12
-- wrote down the same reasoning for `skill_grant`.
--
-- **Revocation is a timestamp, not a delete**, unlike `skill_grant`. The difference is that a
-- listing has to be able to show a key that was revoked, and "when did it stop working" is the half
-- of that the person actually needs. `audit_event` carries the event; this column carries the state.
CREATE TABLE api_key (
  id             TEXT PRIMARY KEY,
  user_id        TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  name           TEXT NOT NULL,
  note           TEXT,
  token_hash     TEXT NOT NULL UNIQUE,
  scope          TEXT NOT NULL,
  expires_at     TEXT,
  last_used_at   TEXT,
  last_used_from TEXT,
  revoked_at     TEXT,
  created_at     TEXT NOT NULL
);

-- The console lists a person's keys; the token hash is what the request path uses, and it already has
-- a unique index from the UNIQUE constraint above.
CREATE INDEX idx_api_key_user ON api_key(user_id);

COMMENT ON TABLE api_key IS
  'A credential the account issues to itself (ADR 0038). The first-party default: the CLI is handed one of these instead of going through the authorization-code flow.';
COMMENT ON COLUMN api_key.id IS
  'ULID. The console addresses a key by this — the token itself is shown once and never stored in a readable form, so it cannot be the handle.';
COMMENT ON COLUMN api_key.name IS
  'What the person called it, e.g. "laptop" or "ci". A short handle — the reason a list of five keys is legible at a glance. Name is identity; `note` is the intent behind it.';
COMMENT ON COLUMN api_key.note IS
  'Free text: why this key was issued, in the person''s own words. NULL is a real answer — nothing was written — so it is not required at issuance.';
COMMENT ON COLUMN api_key.token_hash IS
  'sha256 of the whole presented string, prefix included. The plaintext exists only in the response that created it (same property as access_token: a database leak is not a credential leak).';
COMMENT ON COLUMN api_key.scope IS
  'Space-separated, same vocabulary as access_token.scope (skills:read / skills:write). A request may use the intersection of this and what the account may do right now — the scope is a ceiling, not a grant.';
COMMENT ON COLUMN api_key.expires_at IS
  'NULL means never. Whether issuance requires one is a deployment setting (ADR 0038 §决定 3); the column has to be able to express both answers.';
COMMENT ON COLUMN api_key.last_used_at IS
  'Throttled: the request path rewrites this only when it is older than the staleness window, so a hot key costs a handful of writes an hour rather than one per request.';
COMMENT ON COLUMN api_key.last_used_from IS
  'The address the last *recorded* use came from, written with last_used_at and therefore sampled at the same staleness window. It exists so that "this key is being used from somewhere new" is answerable at all — worth knowing about a credential that is pasted into machines and never rotates (ADR 0039 §决定 4). Sampled rather than exhaustive: an address that is seen again after some other address was recorded looks new, which is the honest reading of a field that holds one value. NULL until the first recorded use, which is why the first use of a key never reports as a new address.';
COMMENT ON COLUMN api_key.revoked_at IS
  'Set by revocation, which takes effect on the next request — there is no token cache to expire (ADR 0021).';
