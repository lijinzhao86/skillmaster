-- An authorization can now say that it is over.
--
-- Before this the fact existed only as a property of the whole set of child rows — "every code,
-- access token and refresh token under this authorization is marked" — and that inference is what let
-- a revoked authorization come back. A refresh already in flight reads the live-row set, finds
-- nothing live because the revocation has just swept it, concludes "this must be the first insert"
-- rather than "this was ended", and writes its newly minted refresh token live. The authorization
-- returned with the rest of its ceiling still to run.
--
-- With the fact on the parent, what a token is worth no longer depends on a sweep having been
-- complete: the load path refuses everything under a revoked authorization, so a live leftover row is
-- inert. The child rows are still marked — for the audit trail, and because liveRefreshHash asks them
-- a question — but they are no longer what "revoked" means.
ALTER TABLE oauth_authorization ADD COLUMN revoked_at TEXT;

COMMENT ON COLUMN oauth_authorization.revoked_at IS
  'When this authorization stopped being usable; NULL while it is live. Written by POST /oauth/revoke, skillmaster logout, revokeAllFor, and the window-outside replay rule (ADR 0024). The authority for "is this authorization over" — the three token tables are marked to match, but a token row that escaped that sweep still cannot be used.';
