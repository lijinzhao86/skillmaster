-- The row V5 was missing: one per act of consent.
--
-- V5 gave the three token tables an `authorization_id` but nothing for it to point at, and no home
-- for the framework's attributes. Writing the real storage is what showed the gap, and the reason it
-- cannot be worked around is PKCE: `CodeVerifierAuthenticator` reads the authorization request back
-- out of `OAuth2Authorization.getAttribute(OAuth2AuthorizationRequest.class.getName())` and compares
-- its `code_challenge` against the presented `code_verifier`. The authorization endpoint put it there;
-- if the store drops it, the code exchange cannot be completed at all. It fails loudly rather than
-- silently (`"authorizationRequest cannot be null"`), which is the only good thing about it.
--
-- A sixth migration rather than an edit to V5, by the same rule V3, V4 and V5 were written under: V5
-- has been applied wherever this branch has run, and Flyway rejects a changed checksum. That it is
-- four minutes old does not matter — "applied anywhere" is the test, not "committed".
--
-- This does not undo V5's split. The three token tables keep their own lifetimes; what arrives here is
-- their parent: the consent, the attributes and the scopes, which outlive every token underneath.

CREATE TABLE oauth_authorization (
  id                   TEXT PRIMARY KEY,
  registered_client_id TEXT NOT NULL,
  principal_name       TEXT NOT NULL,
  grant_type           TEXT NOT NULL,
  authorized_scopes    TEXT NOT NULL,
  attributes           TEXT NOT NULL DEFAULT '{}',
  created_at           TEXT NOT NULL
);

COMMENT ON TABLE oauth_authorization IS
  'One act of consent: the user, the client, the scopes, and whatever the framework needs to carry between the authorization request and the code exchange. The three token tables reference it, so a revocation has one row to reach for and the attributes have somewhere to live that is not a token row. Not the framework''s oauth2_authorization: that one carries the tokens in the same row, which is the shape ADR 0023 declined.';
COMMENT ON COLUMN oauth_authorization.id IS
  'The authorization_id the code and both tokens carry. A ULID, so the order rows were created in is readable from the key.';
COMMENT ON COLUMN oauth_authorization.principal_name IS
  'The userId, not the username — the same value WebAuthentication.getName() returns and that tokens carry as sub (ADR 0014). It is also what the consent record is keyed by, so a mismatch between the two would make an authorization invisible to the check that decides whether to ask again.';
COMMENT ON COLUMN oauth_authorization.grant_type IS
  'authorization_code or client_credentials. Kept because it decides what the row may be used for at the token endpoint, and because an unattended client''s rows are the ones with no consent record behind them.';
COMMENT ON COLUMN oauth_authorization.authorized_scopes IS
  'Space-separated, the scopes this consent covers. A refresh may narrow them and may not widen them (refresh_token.scope).';
COMMENT ON COLUMN oauth_authorization.attributes IS
  'JSON. The framework''s own bag, and the reason this table exists: OAuth2AuthorizationRequest lives here, and the PKCE check reads it back out. **Do not treat this as ours to prune** — a key dropped here breaks a flow that no test in M1 would notice.';

-- Now that the parent exists, the columns V5 added without one can point at it.
ALTER TABLE auth_code
  ADD CONSTRAINT fk_auth_code_authorization
  FOREIGN KEY (authorization_id) REFERENCES oauth_authorization(id);
ALTER TABLE access_token
  ADD CONSTRAINT fk_access_token_authorization
  FOREIGN KEY (authorization_id) REFERENCES oauth_authorization(id);
ALTER TABLE refresh_token
  ADD CONSTRAINT fk_refresh_token_authorization
  FOREIGN KEY (authorization_id) REFERENCES oauth_authorization(id);

-- Nothing else is needed to rebuild the framework's object model. Per-token metadata — the
-- invalidated flag above all — is derivable: the framework marks a used code or a spent refresh
-- token invalid, and we record the same events as revoked_at, so isActive() can be answered from the
-- columns we already have. Claims are the one thing not stored, and only a JWT or an introspection
-- response would want them; ADR 0021 chose opaque tokens and validates them in process.
