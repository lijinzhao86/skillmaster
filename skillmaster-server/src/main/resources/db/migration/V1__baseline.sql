-- Baseline schema for v1-hosting: every table of technical-design.md ch.3.
--
-- Built in one migration rather than one per module because skill.created_by references
-- app_user, so the identity tables have to exist anyway; splitting them would only mean
-- churning the baseline again in P1.
--
-- Two deliberate divergences from the design doc, each explained where it appears:
--   1. `audit_event.id` is GENERATED ALWAYS AS IDENTITY (ADR 0010 decision, doc updated).
--   2. No TABLESPACE clause (below).
--
-- No extensions are required. The Chinese-search index (pg_bigm) is deliberately absent:
-- P0 queries L1 with LIKE, and pg_bigm is a pure index that can be added later without
-- changing a single query. That keeps P0 runnable on a stock PostgreSQL.
--
-- Every table and column carries a COMMENT, and those comments are the only place a column's
-- meaning is written down here — the annotations that used to sit beside each column were moved
-- into them rather than copied, because one description living in two places is a description
-- that drifts. The prose blocks above each table are a different kind of statement: they say why
-- the table exists, which is not what a COMMENT is for. Read them with `psql \d+ <table>`.

-- ---------------------------------------------------------------------------
-- 3.1 Identity and authentication
-- ---------------------------------------------------------------------------

-- `user` is a RESERVED word in PostgreSQL (verified: pg_get_keywords() reports catcode 'R'),
-- so it cannot name this table unquoted. Renamed rather than quoted: a quoted "user" is a
-- footgun no compiler would catch, and the table name carries no external contract.
CREATE TABLE app_user (
  id           TEXT PRIMARY KEY,
  handle       TEXT NOT NULL UNIQUE,
  display_name TEXT NOT NULL DEFAULT '',
  email        TEXT UNIQUE,
  status       TEXT NOT NULL DEFAULT 'active',
  created_at   TEXT NOT NULL
);

COMMENT ON TABLE app_user IS
  'A person or account. Only M1 may read it; every other module reaches it through AccountDirectory.';
COMMENT ON COLUMN app_user.id IS
  'ULID. The identity every reference points at; it never appears in a URL (ADR 0004).';
COMMENT ON COLUMN app_user.handle IS
  'Login name, and the slug of this user''s personal namespace (3.2). UNIQUE, which is what keeps a reserved name out of reach.';
COMMENT ON COLUMN app_user.display_name IS
  'What to show a human. May be empty.';
COMMENT ON COLUMN app_user.email IS
  'Optional, and unique where present.';
COMMENT ON COLUMN app_user.status IS
  'active | suspended. A suspended account keeps its rows; what changes is whether it can authenticate.';
COMMENT ON COLUMN app_user.created_at IS
  'RFC3339 UTC.';

CREATE TABLE credential (
  user_id     TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  type        TEXT NOT NULL,
  secret_hash TEXT NOT NULL,
  updated_at  TEXT NOT NULL,
  PRIMARY KEY (user_id, type)
);

COMMENT ON TABLE credential IS
  'A self-hosted login credential. Unused until P1: there is no login page yet.';
COMMENT ON COLUMN credential.user_id IS
  'The account this credential belongs to.';
COMMENT ON COLUMN credential.type IS
  'password | totp.';
COMMENT ON COLUMN credential.secret_hash IS
  'A hash of the secret, never the secret itself.';
COMMENT ON COLUMN credential.updated_at IS
  'RFC3339 UTC; moved when the secret changes.';

CREATE TABLE identity (
  provider    TEXT NOT NULL,
  external_id TEXT NOT NULL,
  user_id     TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  linked_at   TEXT NOT NULL,
  PRIMARY KEY (provider, external_id)
);

COMMENT ON TABLE identity IS
  'An account at an external identity provider, linked to a local one. Reserved for SSO; costs nothing now.';
COMMENT ON COLUMN identity.provider IS
  'lark | github | google.';
COMMENT ON COLUMN identity.external_id IS
  'The provider''s own identifier for the account.';
COMMENT ON COLUMN identity.user_id IS
  'The local account it is linked to.';
COMMENT ON COLUMN identity.linked_at IS
  'RFC3339 UTC.';

CREATE TABLE browser_session (
  session_id TEXT PRIMARY KEY,
  user_id    TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  expires_at TEXT NOT NULL,
  created_at TEXT NOT NULL
);

COMMENT ON TABLE browser_session IS
  'The login page''s browser session. P1: there is no login page yet.';
COMMENT ON COLUMN browser_session.session_id IS
  'Opaque session identifier, as carried in the cookie.';
COMMENT ON COLUMN browser_session.user_id IS
  'The account the session is for.';
COMMENT ON COLUMN browser_session.expires_at IS
  'RFC3339 UTC.';
COMMENT ON COLUMN browser_session.created_at IS
  'RFC3339 UTC.';

CREATE TABLE oauth_client (
  client_id          TEXT PRIMARY KEY,
  name               TEXT NOT NULL,
  registration       TEXT NOT NULL,
  redirect_uris      TEXT NOT NULL,
  grant_types        TEXT NOT NULL,
  client_secret_hash TEXT,
  metadata           TEXT NOT NULL DEFAULT '{}',
  created_at         TEXT NOT NULL
);

COMMENT ON TABLE oauth_client IS
  'A registered OAuth client. v1 registers only our own CLI (ADR 0011); CIMD arrives with P2''s MCP adapter, and DCR is never enabled.';
COMMENT ON COLUMN oauth_client.client_id IS
  'For a CIMD client this is an HTTPS URL; otherwise an opaque identifier.';
COMMENT ON COLUMN oauth_client.name IS
  'What to show on a consent screen.';
COMMENT ON COLUMN oauth_client.registration IS
  'cimd | dcr | preregistered. v1 enables preregistered only.';
COMMENT ON COLUMN oauth_client.redirect_uris IS
  'JSON array. The token request''s redirect_uri must be one of them.';
COMMENT ON COLUMN oauth_client.grant_types IS
  'JSON array.';
COMMENT ON COLUMN oauth_client.client_secret_hash IS
  'Confidential clients only, and hashed — never the secret itself.';
COMMENT ON COLUMN oauth_client.metadata IS
  'JSON object, as registered.';
COMMENT ON COLUMN oauth_client.created_at IS
  'RFC3339 UTC.';

CREATE TABLE auth_code (
  code_hash      TEXT PRIMARY KEY,
  client_id      TEXT NOT NULL REFERENCES oauth_client(client_id),
  user_id        TEXT NOT NULL REFERENCES app_user(id),
  redirect_uri   TEXT NOT NULL,
  scope          TEXT NOT NULL,
  code_challenge TEXT NOT NULL,
  method         TEXT NOT NULL,
  resource       TEXT,
  expires_at     TEXT NOT NULL,
  used_at        TEXT
);

COMMENT ON TABLE auth_code IS
  'A one-time authorization code. Short-lived, single-use, and bound to the client and redirect URI it was issued for.';
COMMENT ON COLUMN auth_code.code_hash IS
  'A hash of the code, never the code itself (3.1 implementation constraint 1).';
COMMENT ON COLUMN auth_code.client_id IS
  'The client the code was issued to; the token request must present the same one.';
COMMENT ON COLUMN auth_code.user_id IS
  'The account that authorized.';
COMMENT ON COLUMN auth_code.redirect_uri IS
  'The redirect the code was issued for; the token request must present the same one.';
COMMENT ON COLUMN auth_code.scope IS
  'Space-separated scopes granted at authorization.';
COMMENT ON COLUMN auth_code.code_challenge IS
  'The PKCE challenge presented at authorization.';
COMMENT ON COLUMN auth_code.method IS
  'S256 — the only PKCE method accepted.';
COMMENT ON COLUMN auth_code.resource IS
  'RFC 8707 audience the client asked for, when it asked for one.';
COMMENT ON COLUMN auth_code.expires_at IS
  'RFC3339 UTC.';
COMMENT ON COLUMN auth_code.used_at IS
  'When it was redeemed. Non-null means the code is spent, and a second use is an attack rather than a retry.';

-- Tokens are stored as sha256 hashes only, never in clear (3.1 implementation constraint 1).
CREATE TABLE access_token (
  token_hash TEXT PRIMARY KEY,
  client_id  TEXT NOT NULL,
  user_id    TEXT NOT NULL REFERENCES app_user(id),
  scope      TEXT NOT NULL,
  audience   TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  revoked_at TEXT,
  created_at TEXT NOT NULL
);
CREATE INDEX idx_at_expiry ON access_token(expires_at);

COMMENT ON TABLE access_token IS
  'An issued bearer token. Only its hash is stored, so a database leak is not a token leak (3.1 implementation constraint 1).';
COMMENT ON COLUMN access_token.token_hash IS
  'sha256 of the token, never the token itself.';
COMMENT ON COLUMN access_token.client_id IS
  'The client the token was issued to. No foreign key: a token outlives a client row being re-registered.';
COMMENT ON COLUMN access_token.user_id IS
  'The subject the token acts as. P0 resolves one static token to a seeded user instead.';
COMMENT ON COLUMN access_token.scope IS
  'Space-separated scopes, which decide GET versus write authority.';
COMMENT ON COLUMN access_token.audience IS
  'Must be validated: only tokens minted for this service are accepted (3.1 implementation constraint 2).';
COMMENT ON COLUMN access_token.expires_at IS
  'RFC3339 UTC. Indexed, because expiry is swept by time rather than by token.';
COMMENT ON COLUMN access_token.revoked_at IS
  'RFC3339 UTC, or null while the token is live.';
COMMENT ON COLUMN access_token.created_at IS
  'RFC3339 UTC.';

CREATE TABLE refresh_token (
  token_hash   TEXT PRIMARY KEY,
  client_id    TEXT NOT NULL,
  user_id      TEXT NOT NULL REFERENCES app_user(id),
  scope        TEXT NOT NULL,
  expires_at   TEXT NOT NULL,
  revoked_at   TEXT,
  rotated_from TEXT,
  created_at   TEXT NOT NULL
);

COMMENT ON TABLE refresh_token IS
  'A refresh token. Rotated on every use, so a replay is detectable and takes the whole chain down with it (3.1 implementation constraint 3).';
COMMENT ON COLUMN refresh_token.token_hash IS
  'sha256 of the token, never the token itself.';
COMMENT ON COLUMN refresh_token.client_id IS
  'The client the token was issued to. No foreign key, as on access_token.';
COMMENT ON COLUMN refresh_token.user_id IS
  'The subject the token refreshes for.';
COMMENT ON COLUMN refresh_token.scope IS
  'Space-separated scopes; a refresh may narrow them and may not widen them.';
COMMENT ON COLUMN refresh_token.expires_at IS
  'RFC3339 UTC.';
COMMENT ON COLUMN refresh_token.revoked_at IS
  'RFC3339 UTC, or null while the token is live.';
COMMENT ON COLUMN refresh_token.rotated_from IS
  'The token this one replaced. Presenting any ancestor again is a replay, and the chain is revoked.';
COMMENT ON COLUMN refresh_token.created_at IS
  'RFC3339 UTC.';

-- ---------------------------------------------------------------------------
-- 3.2 Namespaces and membership
-- ---------------------------------------------------------------------------

CREATE TABLE namespace (
  id            TEXT PRIMARY KEY,
  slug          TEXT NOT NULL UNIQUE,
  title         TEXT NOT NULL DEFAULT '',
  owner_user_id TEXT NOT NULL REFERENCES app_user(id),
  visibility    TEXT NOT NULL DEFAULT 'private',
  created_at    TEXT NOT NULL
);

COMMENT ON TABLE namespace IS
  'A scope in which a skill name is unique. v1 gives every user exactly one, created at registration with slug = handle (3.2).';
COMMENT ON COLUMN namespace.id IS
  'ULID. What a skill row references; the address uses the slug instead.';
COMMENT ON COLUMN namespace.slug IS
  'The first segment of a skill''s address (4.1). UNIQUE, which is what reserves a name like ''skillmaster'' for the gateway.';
COMMENT ON COLUMN namespace.title IS
  'What to show a human. May be empty.';
COMMENT ON COLUMN namespace.owner_user_id IS
  'The account that owns it. v1 has no sharing, so this is also the only account that may read from it.';
COMMENT ON COLUMN namespace.visibility IS
  'public | unlisted | private. v1 only ever writes private; the other two are reserved for v2 (3.2).';
COMMENT ON COLUMN namespace.created_at IS
  'RFC3339 UTC.';

CREATE TABLE namespace_member (
  namespace_id TEXT NOT NULL REFERENCES namespace(id) ON DELETE CASCADE,
  user_id      TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  role         TEXT NOT NULL,
  added_at     TEXT NOT NULL,
  PRIMARY KEY (namespace_id, user_id)
);

COMMENT ON TABLE namespace_member IS
  'Who belongs to a namespace, and as what. The primary key is the pair, because a membership is a relationship rather than a thing.';
COMMENT ON COLUMN namespace_member.namespace_id IS
  'The namespace.';
COMMENT ON COLUMN namespace_member.user_id IS
  'The account.';
COMMENT ON COLUMN namespace_member.role IS
  'owner | editor | viewer. v1 writes owner only; membership management is deferred (3.2).';
COMMENT ON COLUMN namespace_member.added_at IS
  'RFC3339 UTC.';

-- ---------------------------------------------------------------------------
-- 3.3 Skills, versions, content-addressed blobs
-- ---------------------------------------------------------------------------

-- Bytes live in their own table, and the metadata row is separate from them, so the two can
-- be backed up on different schedules and so blob pages cannot evict metadata pages from
-- shared_buffers (ADR 0010 decision 2).
--
-- The design doc places blob_content in TABLESPACE blob_ts. That clause is NOT used here:
-- a tablespace has to exist before it can be named, and CREATE TABLESPACE requires superuser,
-- so it cannot be created from a migration. Moving the table is a deployment step:
--   ALTER TABLE blob_content SET TABLESPACE blob_ts;
CREATE TABLE blob (
  sha256     TEXT PRIMARY KEY,
  size       INTEGER NOT NULL,
  created_at TEXT NOT NULL
);

COMMENT ON TABLE blob IS
  'One row per distinct piece of content, addressed by its own hash. Deduplication is a property of the primary key, not a feature.';
COMMENT ON COLUMN blob.sha256 IS
  'Lowercase hex sha256 of the bytes. The content''s identity, shared across skills and versions.';
COMMENT ON COLUMN blob.size IS
  'Bytes, recorded once when the content was stored. A manifest carries its own per-file size from version_file, so no read path consults this today.';
COMMENT ON COLUMN blob.created_at IS
  'RFC3339 UTC.';

CREATE TABLE blob_content (
  sha256 TEXT PRIMARY KEY REFERENCES blob(sha256) ON DELETE CASCADE,
  bytes  BYTEA NOT NULL
);

COMMENT ON TABLE blob_content IS
  'The bytes themselves. Split from blob so the two can be backed up on different schedules and so blob pages cannot evict metadata pages (ADR 0010 decision 2).';
COMMENT ON COLUMN blob_content.sha256 IS
  'The same hash as blob.sha256: one row split in two, not two facts.';
COMMENT ON COLUMN blob_content.bytes IS
  'The content exactly as uploaded. Nothing on any path rewrites these bytes, because the digest describes them (ADR 0005).';

-- `id` is the identity and `name` is an attribute (ADR 0004): a rename is an UPDATE to
-- skill.name and nothing else — no URL, reference, or audit row moves.
-- `current_version_id` deliberately has no foreign key: it points at skill_version, which
-- points back at skill, so a FK would be circular. The pointer is only ever moved inside
-- the publish transaction.
CREATE TABLE skill (
  id                 TEXT PRIMARY KEY,
  namespace_id       TEXT NOT NULL REFERENCES namespace(id) ON DELETE CASCADE,
  name               TEXT NOT NULL,
  title              TEXT NOT NULL DEFAULT '',
  description        TEXT NOT NULL,
  frontmatter        TEXT NOT NULL,
  visibility         TEXT NOT NULL DEFAULT 'private',
  current_version_id TEXT,
  created_by         TEXT NOT NULL REFERENCES app_user(id),
  created_at         TEXT NOT NULL,
  updated_at         TEXT NOT NULL,
  deleted_at         TEXT,
  UNIQUE (namespace_id, name)
);
CREATE INDEX idx_skill_ns  ON skill(namespace_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_skill_vis ON skill(visibility)   WHERE deleted_at IS NULL;

COMMENT ON TABLE skill IS
  'A skill''s identity: the name it is addressed by within a namespace, the metadata shown when listing it, and a pointer to its current version.';
COMMENT ON COLUMN skill.id IS
  'ULID, the identity. A rename updates name and nothing else — no URL, reference or audit row moves (ADR 0004).';
COMMENT ON COLUMN skill.namespace_id IS
  'The namespace that makes the name unique.';
COMMENT ON COLUMN skill.name IS
  'The name from the frontmatter, which is the last segment of the address (4.1). Unique within the namespace.';
COMMENT ON COLUMN skill.title IS
  'Display title; falls back to the name when the frontmatter has none.';
COMMENT ON COLUMN skill.description IS
  'Denormalised from the frontmatter because search reads it on the hot path (3.4).';
COMMENT ON COLUMN skill.frontmatter IS
  'The parsed frontmatter as JSON, unknown fields included. M7 has no opinion about its shape (3.3).';
COMMENT ON COLUMN skill.visibility IS
  'public | unlisted | private. v1 only ever writes private.';
COMMENT ON COLUMN skill.current_version_id IS
  'The current version, or null only between the two halves of the first publish''s transaction. No foreign key: it points at skill_version, which points back here, so a FK would be circular (3.3).';
COMMENT ON COLUMN skill.created_by IS
  'The account that first published it.';
COMMENT ON COLUMN skill.created_at IS
  'RFC3339 UTC.';
COMMENT ON COLUMN skill.updated_at IS
  'RFC3339 UTC. A metadata edit moves it without creating a version (4.3), and the listing sorts on it.';
COMMENT ON COLUMN skill.deleted_at IS
  'Soft delete. Non-null takes the skill off every read path while its versions and their files stay in place (3.3).';

-- UNIQUE (skill_id, digest) is what makes publishing idempotent: identical content does not
-- produce a new version, so no "read then write" race exists (ADR 0005).
--
-- `number` is that rule's companion rather than a replacement (ADR 0012). It counts the skill's Nth
-- *distinct* content, so republishing identical content consumes no number — otherwise one digest
-- would hold two numbers and the idempotence above would be dead. UNIQUE (skill_id, number) is what
-- makes the number an immutable alias: `@3` always names the same content, like a git tag rather
-- than a branch, which is the whole basis of a stable address.
CREATE TABLE skill_version (
  id           TEXT PRIMARY KEY,
  skill_id     TEXT NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
  number       INTEGER NOT NULL,
  digest       TEXT NOT NULL,
  file_count   INTEGER NOT NULL,
  total_bytes  INTEGER NOT NULL,
  changelog    TEXT NOT NULL DEFAULT '',
  source       TEXT NOT NULL DEFAULT '',
  published_by TEXT NOT NULL REFERENCES app_user(id),
  published_at TEXT NOT NULL,
  UNIQUE (skill_id, digest),
  UNIQUE (skill_id, number)
);
CREATE INDEX idx_ver_skill ON skill_version(skill_id, published_at DESC);

COMMENT ON TABLE skill_version IS
  'One immutable snapshot: a skill''s Nth distinct file set, with who published it and when. Never updated, only inserted.';
COMMENT ON COLUMN skill_version.id IS
  'ULID, a row key only. The version''s identity is digest; number is an alias for it (ADR 0012).';
COMMENT ON COLUMN skill_version.skill_id IS
  'The skill this is a version of. Numbers are per-skill, so the uniqueness of both keys is too.';
COMMENT ON COLUMN skill_version.number IS
  'The skill''s Nth distinct content, never reused — what @N in an address names (ADR 0012). An alias, not an identity: digest is the identity.';
COMMENT ON COLUMN skill_version.digest IS
  'Deterministic digest over the file set (ADR 0005). It is the version''s identity, and what makes republishing identical content a no-op.';
COMMENT ON COLUMN skill_version.file_count IS
  'Files in the manifest.';
COMMENT ON COLUMN skill_version.total_bytes IS
  'Sum of the manifest''s file sizes.';
COMMENT ON COLUMN skill_version.changelog IS
  'Free text from the author. v1 has no endpoint that sets it.';
COMMENT ON COLUMN skill_version.source IS
  'Provenance note: upload | zip | git url.';
COMMENT ON COLUMN skill_version.published_by IS
  'The account that published it — the system account when the server publishes its own gateway skill.';
COMMENT ON COLUMN skill_version.published_at IS
  'RFC3339 UTC, and the version''s own timestamp: a replay of identical content keeps the original rather than moving it.';

-- The manifest is this table projected and explicitly sorted by relpath — never by whatever
-- order the database happens to return (ADR 0005 后果).
CREATE TABLE version_file (
  version_id  TEXT NOT NULL REFERENCES skill_version(id) ON DELETE CASCADE,
  relpath     TEXT NOT NULL,
  blob_sha256 TEXT NOT NULL REFERENCES blob(sha256),
  size        INTEGER NOT NULL,
  is_binary   INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (version_id, relpath)
);

COMMENT ON TABLE version_file IS
  'The manifest: which path of which version holds which bytes. Projected and explicitly sorted by relpath when read (ADR 0005 后果).';
COMMENT ON COLUMN version_file.version_id IS
  'The version this entry belongs to.';
COMMENT ON COLUMN version_file.relpath IS
  'Path relative to the skill root, POSIX separators, no leading slash and no empty segment (3.3).';
COMMENT ON COLUMN version_file.blob_sha256 IS
  'The content at that path. Shared with every other version that holds the same bytes.';
COMMENT ON COLUMN version_file.size IS
  'Bytes.';
COMMENT ON COLUMN version_file.is_binary IS
  '1 when the bytes are not text, so a client can tell without fetching them.';

-- ---------------------------------------------------------------------------
-- 3.4 Search and usage statistics
-- ---------------------------------------------------------------------------

-- Only L1 is indexed — name, title, description. Never the body or the files: that is a
-- safety constraint, not a performance one, because a full-text index over the body would
-- let fragments of it be reconstructed through search (ADR 0006).
--
-- **No usage-counter table lives here.** The baseline once carried `skill_stat`, marked "written
-- in P1"; nothing ever read or wrote it, so v3 removed it rather than leave a table in the schema
-- that no code owns (v3 prd.md, 范围 #5). Nothing is reserved in its place: P1 will create the
-- table it actually wants, on the day there is a signal to count.

-- ---------------------------------------------------------------------------
-- 3.5 Audit
-- ---------------------------------------------------------------------------

-- Publishing, deleting, rolling back, and issuing or revoking a token all leave a trace:
-- skill content is taken away and used in client environments, so after-the-fact attribution
-- is the floor (3.5).
CREATE TABLE audit_event (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  actor_user_id TEXT,
  action        TEXT NOT NULL,
  target_type   TEXT NOT NULL,
  target_id     TEXT,
  detail        TEXT NOT NULL DEFAULT '{}',
  at            TEXT NOT NULL
);
CREATE INDEX idx_audit_at ON audit_event(at DESC);

COMMENT ON TABLE audit_event IS
  'An append-only trace, written inside the same transaction as the change it describes (3.5).';
COMMENT ON COLUMN audit_event.id IS
  'Identity column, never reused. Deliberately not a ULID: this table is append-only and the largest here, and its id never enters a URL.';
COMMENT ON COLUMN audit_event.actor_user_id IS
  'Who acted. Null when no authenticated account can be named.';
COMMENT ON COLUMN audit_event.action IS
  'publish | delete | rollback | token_issue | ...';
COMMENT ON COLUMN audit_event.target_type IS
  'What kind of thing was acted on, e.g. skill.';
COMMENT ON COLUMN audit_event.target_id IS
  'Its id — the identity, not the address it was reached through (ADR 0004).';
COMMENT ON COLUMN audit_event.detail IS
  'JSON object carrying whatever the action is worth recording, such as a name and a digest.';
COMMENT ON COLUMN audit_event.at IS
  'RFC3339 UTC. Indexed, because the newest entries are what an investigation reads first.';
