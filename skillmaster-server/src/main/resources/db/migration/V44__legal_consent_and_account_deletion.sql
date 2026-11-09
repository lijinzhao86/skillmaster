-- v56: legal consent at registration, and the timestamp a deletion becomes due (ADR 0175).
--
-- **No new status value is introduced.** `app_user.status` already says whether an account may act;
-- `deleted` is a third value beside `active` and `suspended`, and `AccountDirectory.isActive`
-- already refuses anything that is not `active`. `deleted_at` exists for one reader only — the daily
-- purge, which needs to know when the retention window opened.
--
-- **The account row is kept, de-identified, and not deleted** (ADR 0175 §决定 3). Other people's
-- content points at it: `skill.created_by`, `skill_version.submitted_by`, `skill_grant.granted_by`.
-- Deleting the row would either break those references or erase somebody else's history. The purge
-- removes every personal column instead — phone, password, credentials, notifications, consent —
-- and rewrites the handle to `deleted-<id>` with the display name 「已注销账号」.

ALTER TABLE app_user ADD COLUMN deleted_at TEXT;

-- When the purge actually ran. **Without this the job would select the same account every day**:
-- `status` stays `deleted` and `deleted_at` is the original act, so "due" would never become false.
-- It is also the honest record of when the thirty days ended.
ALTER TABLE app_user ADD COLUMN purged_at TEXT;

-- The purge's only query: which accounts have been deleted long enough. Partial, because every
-- other status is irrelevant to it and the index stays small for ever.
CREATE INDEX app_user_deletion_due ON app_user (deleted_at) WHERE status = 'deleted';

COMMENT ON COLUMN app_user.deleted_at IS
  'When the person deleted the account. Null until then; the purge reads it against the retention window.';
COMMENT ON COLUMN app_user.purged_at IS
  'When the purge removed the personal rows and de-identified this one. Set once; the purge skips accounts that have it.';
COMMENT ON COLUMN app_user.status IS
  'active | suspended | deleted. Deleted may not act and is due for purge at deleted_at; the row itself survives, de-identified.';

-- One row per account, both versions on it: they were agreed in the same checkbox, at the same
-- moment. A future re-consent needs history, and that is a new table (or a new column pair) then,
-- not a guess now.
CREATE TABLE legal_consent (
  user_id         TEXT PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
  terms_version   TEXT NOT NULL,
  privacy_version TEXT NOT NULL,
  consented_at    TEXT NOT NULL
);

COMMENT ON TABLE legal_consent IS
  'Which versions of the terms and the privacy policy an account accepted at registration. Server-side authority; the client never supplies a version.';
