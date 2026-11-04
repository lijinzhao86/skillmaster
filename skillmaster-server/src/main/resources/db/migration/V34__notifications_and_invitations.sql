-- v45: the notification floor and invite-based sharing (ADR 0138, ADR 0139).
--
-- A notification is a pointer at a business object, not a second copy of its state: the row says
-- who did what, the business table says whether it can still be answered. Read state is per
-- recipient, so one announcement body serves everyone.
--
-- `skill_invitation` is the share's own object, shaped like `skill_access_request`: the row's
-- presence means "waiting", accepting writes `skill_grant` and deletes the row, and the 30-day
-- expiry is read from `expires_at` rather than swept by a background task.

CREATE TABLE notification (
  id           TEXT PRIMARY KEY,
  kind         TEXT NOT NULL,
  subject_key  TEXT,
  actor_id     TEXT REFERENCES app_user(id) ON DELETE SET NULL,
  actor_handle TEXT,
  skill_id     TEXT REFERENCES skill(id) ON DELETE CASCADE,
  role         TEXT,
  outcome      TEXT,
  expires_at   TEXT,
  title        TEXT,
  body         TEXT,
  created_at   TEXT NOT NULL,
  resolved_at  TEXT,
  CONSTRAINT notification_outcome_known CHECK (
    outcome IS NULL
    OR outcome IN ('accepted', 'declined', 'expired', 'revoked',
                   'approved', 'rejected', 'withdrawn')),
  CONSTRAINT notification_announcement_shaped CHECK (
    kind <> 'announcement'
    OR (subject_key IS NULL AND actor_id IS NULL AND skill_id IS NULL
        AND title IS NOT NULL))
);

-- One unresolved notification per business object per kind. Re-sharing or re-requesting moves the
-- open row instead of stacking a second one; once it is resolved, the same subject can open again
-- (a new invitation after the old one expired) while the old row stays as history.
CREATE UNIQUE INDEX uq_notification_open_subject
  ON notification (subject_key, kind)
  WHERE resolved_at IS NULL AND subject_key IS NOT NULL;

CREATE INDEX idx_notification_created ON notification (created_at DESC, id DESC);
CREATE INDEX idx_notification_skill ON notification (skill_id);

COMMENT ON TABLE notification IS
  'One event, one body. A request-shaped row points at its business object by subject_key and carries only the result snapshot; it never replaces that object as the authority.';
COMMENT ON COLUMN notification.kind IS
  'Open vocabulary: share_invitation / access_request / access_request_result / announcement today. New kinds need no migration.';
COMMENT ON COLUMN notification.subject_key IS
  'invitation:<skillId>:<inviteeId> or access_request:<skillId>:<requesterId>; NULL for announcements.';
COMMENT ON COLUMN notification.actor_handle IS
  'The actor''s handle at the moment the notification was written, kept as history rather than resolved later.';
COMMENT ON COLUMN notification.expires_at IS
  'Only invitations carry one (created_at + 30 days). Expiry is read, never swept.';

CREATE TABLE notification_recipient (
  notification_id TEXT NOT NULL REFERENCES notification(id) ON DELETE CASCADE,
  user_id         TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  read_at         TEXT,
  created_at      TEXT NOT NULL,
  PRIMARY KEY (notification_id, user_id)
);

CREATE INDEX idx_notification_recipient_user
  ON notification_recipient (user_id);
CREATE INDEX idx_notification_recipient_unread
  ON notification_recipient (user_id, read_at);

COMMENT ON TABLE notification_recipient IS
  'Per-recipient read state. A broadcast is one notification row and one row here per reader, so the body is stored once.';

CREATE TABLE skill_invitation (
  skill_id  TEXT NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
  invitee_id TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  role      TEXT NOT NULL,
  invited_by TEXT NOT NULL REFERENCES app_user(id),
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  PRIMARY KEY (skill_id, invitee_id)
);

CREATE INDEX idx_skill_invitation_invitee ON skill_invitation (invitee_id);

COMMENT ON TABLE skill_invitation IS
  'A share waiting to be accepted. The row''s presence is the state: accept writes skill_grant and deletes it; decline or revoke deletes it; expiry is expires_at <= now.';
COMMENT ON COLUMN skill_invitation.role IS
  'The rung the inviter offered. Accepting grants exactly this rung.';
COMMENT ON COLUMN skill_invitation.expires_at IS
  'created_at + 30 days, fixed for this version (ADR 0138).';
