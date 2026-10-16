-- When a first-party CLI login was last used. v4.
--
-- **The value is derivable, and deriving it would be wrong exactly where it matters.** A login's
-- expiry slides to `now + idle` on every use, so `expires_at - idle` is the time of the last use —
-- until the absolute ceiling binds, after which `expires_at` stops moving while the real last use
-- carries on. That is the final stretch of the credential's life, which is precisely when a screen
-- saying "last active …" is worth reading. The console's row shows it, and the ruler it explains is
-- the idle one, so an answer that is a day out is worse than no answer.
--
-- **Sampled at the same window as the expiry it sits beside** — the write happens only when the
-- recorded expiry is more than a slide window stale, so a busy machine costs a handful of writes an
-- hour rather than one per request. That is `api_key.last_used_at`'s arrangement and its wording, on
-- purpose: two credentials, one convention for "when was this last used", and the same honest caveat
-- that the answer can lag by up to one window.
--
-- **NULL until the first recorded use**, which is a real answer rather than a gap: a login that was
-- approved and never used has no last use, and its `created_at` is the only thing it can be asked.
ALTER TABLE oauth_authorization ADD COLUMN last_used_at TEXT;

COMMENT ON COLUMN oauth_authorization.last_used_at IS
  'RFC3339 UTC, written by the same statement that slides the expiry, and sampled at the same slide window — so it can lag one window behind the truth. NULL until the first recorded use. NULL for every framework-written row, which has its own notion of activity.';
