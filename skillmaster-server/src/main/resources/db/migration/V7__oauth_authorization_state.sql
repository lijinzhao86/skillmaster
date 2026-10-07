-- The second gap found by writing the storage, and it is the same kind as V6's.
--
-- V6 gave the framework's attributes a home. It did not give its *state* one, and the consent step
-- looks an authorization up by exactly that: `OAuth2AuthorizationConsentAuthenticationProvider` calls
-- `OAuth2AuthorizationService.findByToken(state, null)` with the value it put in the consent page's
-- redirect, and looks the authorization up from it. Spring's own `oauth2_authorization` table has a
-- `state` column for this; ours had nowhere to put it, so the lookup found nothing and every consent
-- submission was refused with `OAuth 2.0 Parameter: state` — which reads like the client sent the
-- wrong value rather than like the server having nowhere to keep the right one.
--
-- Found the way V6 was: by driving the real endpoints, not by reading. A design review would not have
-- caught it, because the design never mentions the state; only the framework's behaviour does.
--
-- Plain text, deliberately, and this is the one column here that is not a hash. It is a lookup key
-- rather than a credential — the state cannot be exchanged for anything on its own, and the framework
-- compares it by equality against what it stored. Hashing it would make the lookup impossible.
-- §3.1's "store only sha256" is about tokens that grant access.

ALTER TABLE oauth_authorization ADD COLUMN state TEXT;

COMMENT ON COLUMN oauth_authorization.state IS
  'The framework''s own state for an authorization, as it appears in the consent page''s redirect and comes back in the consent form. findByToken(state, null) is how the consent step recovers the authorization, so this column is what makes that step work at all. Null until the authorization request has been handled. Plain text because it is a lookup key, not a credential.';

-- Not a partial index: findByToken looks up a single value and the state is null for most rows only
-- until each row's request is handled, at which point it is set. An index on the whole column is what
-- makes that lookup a point read rather than a scan.
CREATE INDEX idx_oa_state ON oauth_authorization(state);
