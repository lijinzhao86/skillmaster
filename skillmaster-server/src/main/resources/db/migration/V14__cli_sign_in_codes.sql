-- The one-time code that carries an already-minted login credential from the browser back to the CLI,
-- so the credential itself never travels in a URL. ADR 0028 put the fixed loopback port in place for
-- exactly this hand-off; ADR 0040 §决定 1 is why a session, not the code, is what mints.
--
-- **A table, because "once" and "briefly" are state.** The code is a bearer value sitting in an
-- address, and an address lands in browser history, in a Referer and in any log that decides to keep
-- it. Short-lived and single-use are therefore the whole point of it — and neither property can be
-- read off the value itself. This row is where both are recorded.
--
-- **Only the hash**, like the three token tables and api_key: a database leak must not be a
-- credential leak. The plaintext exists in the redirect and in the exchange response, and nowhere
-- else — not in a log, not in an audit row.
--
-- **No foreign key on `authorization_id`.** The authorization was already minted when this code was
-- issued: the code carries it, it does not create it. A row that lives for minutes should end on its
-- own clock, and deleting an authorization is not a reason for an unrelated code to vanish mid-flight.
-- **The cost, recorded rather than hidden:** when an authorization goes away, codes pointing at it
-- stay until their own `expires_at` passes. Nothing sweeps them — which is also true of
-- `access_token`, whose expiry index has no reaper behind it either (V1 says so at that index).
-- (Do **not** reach for `access_token.client_id` as precedent here: that comment is about a token
-- outliving a *client* row being re-registered, and `access_token.authorization_id` **is** a foreign
-- key, added by V6. Citing it would say the opposite of what this table does.)
--
-- **No index.** Every read is by primary key, and the row count is "how many times somebody clicked
-- in the last few minutes".
CREATE TABLE cli_sign_in_code (
  code_hash        TEXT PRIMARY KEY,
  authorization_id TEXT NOT NULL,
  code_challenge   TEXT NOT NULL,
  expires_at       TEXT NOT NULL,
  used_at          TEXT,
  created_at       TEXT NOT NULL
);

COMMENT ON TABLE cli_sign_in_code IS
  'A one-time code carrying an already-minted login credential from the console to the CLI (ADR 0028). The credential itself never appears in a URL.';
COMMENT ON COLUMN cli_sign_in_code.code_hash IS
  'sha256 of the code as presented, hex. The same rule as access_token, refresh_token, auth_code and api_key: the stored form is not the usable one.';
COMMENT ON COLUMN cli_sign_in_code.authorization_id IS
  'The authorization whose access token this code delivers. Already minted when the code was issued — exchanging hands the credential over, it does not create one. No foreign key, deliberately: the code ends on its own expiry, not on the authorization''s.';
COMMENT ON COLUMN cli_sign_in_code.code_challenge IS
  'PKCE S256 challenge, sent by the CLI in the address that opened the sign-in screen. NOT NULL because this client is public: there is no secret, so the proof of possession presented at exchange is the only thing tying the code to the process that asked for it.';
COMMENT ON COLUMN cli_sign_in_code.expires_at IS
  'RFC3339 UTC. Minutes rather than days — the code only has to outlive the redirect. Same length as the authorization server''s own code, and the same setting carries both.';
COMMENT ON COLUMN cli_sign_in_code.used_at IS
  'RFC3339 UTC, set when the code is exchanged. Non-null means spent, and a second exchange is answered exactly like a code that never existed: saying "already used" would confirm to whoever holds a random string that it was once real.';
COMMENT ON COLUMN cli_sign_in_code.created_at IS
  'RFC3339 UTC.';
