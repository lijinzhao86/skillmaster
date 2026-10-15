-- M2's schema (the token / authorization server).
--
-- A fifth migration rather than an edit to V1, which already created the four token tables. V1 is
-- committed and applied wherever this project has been run, and Flyway refuses to start when an
-- applied migration's checksum changes — so editing it would mean rebuilding every database that has
-- one. Same reasoning as V3 and V4's headers; the tables being empty is not a reason to reach back
-- into a migration that has already run.
--
-- Two changes, both of them consequences of decisions taken on 2026-10-02:
--   1. authorization_id on the three   — one authorization's code and both tokens, which is what
--      token tables                       makes revocation immediate and what the framework's object
--                                         model needs to rebuild (ADR 0023)
--   2. oauth2_authorization_consent    — the consent record, which we take from the framework
--                                         rather than write (ADR 0023)
--
-- **A third change — `oauth_client.user_id` — was taken back out of this migration in place (v3).**
-- It existed for the `client_credentials` grant that ADR 0038 §决定 9 replaced with a key; nothing is
-- deployed, so the column left the migration rather than going through a drop of its own, and the one
-- database it had been applied to was rebuilt (v3 prd.md §范围 #5).

-- ---------------------------------------------------------------------------
-- 1. One authorization, one id
-- ---------------------------------------------------------------------------

ALTER TABLE auth_code ADD COLUMN authorization_id TEXT NOT NULL;
ALTER TABLE access_token ADD COLUMN authorization_id TEXT NOT NULL;
ALTER TABLE refresh_token ADD COLUMN authorization_id TEXT NOT NULL;

COMMENT ON COLUMN auth_code.authorization_id IS
  'Groups the code with the tokens it leads to. An authorization is one act of consent, so a refresh and everything it issues keep the same value — which is how a revocation reaches all of them at once. See access_token.authorization_id for the second reason this exists.';
COMMENT ON COLUMN access_token.authorization_id IS
  'Groups this token with the authorization that issued it. Two things depend on it: revoking an authorization can cancel live access tokens immediately instead of waiting out their hour, and Spring Authorization Server''s object model carries a code and both tokens in one object that findByToken has to hand back whole. Without this column the first is impossible and the second is guesswork.';
COMMENT ON COLUMN refresh_token.authorization_id IS
  'Groups this token with the authorization that issued it. Constant along the rotation chain, unlike rotated_from, which only points at the immediately preceding token.';

-- Revocation looks rows up by this column, so it needs to be indexed where the table can grow.
-- auth_code is left out on purpose: its rows live for minutes and are consumed once, so the table
-- stays small enough that a scan costs nothing.
CREATE INDEX idx_at_authorization ON access_token(authorization_id);
CREATE INDEX idx_rt_authorization ON refresh_token(authorization_id);

-- ---------------------------------------------------------------------------
-- 2. Consent
-- ---------------------------------------------------------------------------

-- Copied verbatim from Spring Authorization Server's own oauth2-authorization-consent-schema.sql,
-- apart from letter case. Our other tables use TEXT everywhere, so this one is deliberately unlike
-- them: it is the framework's table, read and written by its JdbcOAuth2AuthorizationConsentService,
-- and keeping it byte-identical to the shipped schema is what lets an upgrade be checked by diff
-- instead of by reading. (The same reasoning built Spring Session's two tables in V3.)
CREATE TABLE oauth2_authorization_consent (
    registered_client_id varchar(100) NOT NULL,
    principal_name varchar(200) NOT NULL,
    authorities varchar(1000) NOT NULL,
    PRIMARY KEY (registered_client_id, principal_name)
);

COMMENT ON TABLE oauth2_authorization_consent IS
  'What each user has agreed to let each client do. Keyed by (client, user) and not by device, which is a deliberate choice with a known price: a user who has consented once is not asked again on a second machine, so anyone holding that session can obtain a CLI credential silently. See the M02 module doc, §同意.';
COMMENT ON COLUMN oauth2_authorization_consent.principal_name IS
  'The userId, not the username — the same value WebAuthentication.getName() returns and that the tokens carry as sub (ADR 0014). Getting this wrong would not fail any test in M1; it would file consent under a name nothing else looks up.';
COMMENT ON COLUMN oauth2_authorization_consent.authorities IS
  'The scopes consented to, as Spring Security authorities. A later authorization asking for more than is listed here is shown the consent page again, which is what keeps a scope widening from being silent.';
