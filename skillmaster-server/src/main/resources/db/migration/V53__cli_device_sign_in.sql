-- One CLI login waiting for a person to answer it (v68, ADR 0190).
--
-- **What it replaces.** `cli_sign_in_code` carried a one-time code from a console click back to a
-- loopback listener. The listener is gone: the CLI now prints a link, the person answers it on
-- whatever browser they have, and the CLI asks the server whether the answer arrived yet. So the
-- state that has to survive is no longer "a code in flight" but "a question waiting".
--
-- **Two secrets, and neither is stored.** The ticket travels in the link (it will land in a chat,
-- a history and possibly a log) and the device secret is returned once to the CLI that asked. The
-- ticket alone cannot redeem anything: the CLI proves possession of the secret and of the PKCE
-- verifier, and the authorization row only exists if a session approved this row.
--
-- **Status is derived, not stored.** `consumed_at` / `denied_at` / `approved_at` / `expires_at`
-- answer every question the routes ask; a `status` column would be a second answer that could
-- disagree with them.
--
-- **No foreign key on `authorization_id`.** The approval writes the authorization row, but the
-- question ends on its own clock — the same reasoning as `cli_sign_in_code` before it. A row that
-- lives for ten minutes should not vanish mid-flight because an unrelated authorization was
-- deleted.
CREATE TABLE cli_device_sign_in (
  ticket_hash        TEXT PRIMARY KEY,
  device_secret_hash TEXT NOT NULL,
  client_id          TEXT NOT NULL,
  scopes             TEXT NOT NULL,
  code_challenge     TEXT NOT NULL,
  name               TEXT NOT NULL,
  address            TEXT,
  created_at         TEXT NOT NULL,
  expires_at         TEXT NOT NULL,
  last_polled_at     TEXT,
  approved_at        TEXT,
  authorization_id   TEXT,
  denied_at          TEXT,
  consumed_at        TEXT
);

COMMENT ON TABLE cli_device_sign_in IS
  'A CLI login waiting to be approved in a browser (v68, ADR 0190). The row is the question; the authorization is the answer.';

COMMENT ON COLUMN cli_device_sign_in.ticket_hash IS
  'sha256 of the ticket carried in the link, hex. The link is a bearer value (it lands in chats and histories), so the stored form is not the usable one.';

COMMENT ON COLUMN cli_device_sign_in.device_secret_hash IS
  'sha256 of the secret handed to the CLI once, hex. Together with the PKCE verifier it is the only thing that can collect the credential — the ticket alone cannot.';

COMMENT ON COLUMN cli_device_sign_in.client_id IS
  'The oauth_client row this login is for; its scopes bound what the approval may grant.';

COMMENT ON COLUMN cli_device_sign_in.scopes IS
  'Space-separated, intersected with the client''s registered scopes at creation. Stored so the page can show what it is agreeing to without a second read of the client row.';

COMMENT ON COLUMN cli_device_sign_in.code_challenge IS
  'PKCE S256 challenge sent by the CLI. Checked when the credential is collected, never before.';

COMMENT ON COLUMN cli_device_sign_in.name IS
  'The machine name the CLI proposed. The approval may change it; the authorization row is what the console lists.';

COMMENT ON COLUMN cli_device_sign_in.address IS
  'The address the request came from, as the server saw it, or NULL when the socket did not expose one. Shown on the approval page so a person can recognise the machine.';

COMMENT ON COLUMN cli_device_sign_in.expires_at IS
  'RFC3339 UTC. Ten minutes: long enough to find a phone, short enough that a forwarded link stops mattering.';

COMMENT ON COLUMN cli_device_sign_in.last_polled_at IS
  'RFC3339 UTC, the last poll that was allowed through. The per-ticket one-second interval is enforced by comparing against this column rather than in memory, so it works across instances.';

COMMENT ON COLUMN cli_device_sign_in.approved_at IS
  'RFC3339 UTC, set by the console approval in the same transaction that writes the authorization row.';

COMMENT ON COLUMN cli_device_sign_in.authorization_id IS
  'The authorization the approval wrote. No foreign key: the question ends on its own clock.';

COMMENT ON COLUMN cli_device_sign_in.denied_at IS
  'RFC3339 UTC, set by the console refusal. A refusal creates no authorization row.';

COMMENT ON COLUMN cli_device_sign_in.consumed_at IS
  'RFC3339 UTC, set when the credential is handed to the CLI. Once, like every credential delivery in this schema.';

CREATE INDEX cli_device_sign_in_expires_at ON cli_device_sign_in (expires_at);

-- The loopback delivery this table served is gone (ADR 0190 §决定 4). Nothing reads or writes it
-- after this version; leaving it would be a second delivery path that looks alive.
DROP TABLE cli_sign_in_code;
