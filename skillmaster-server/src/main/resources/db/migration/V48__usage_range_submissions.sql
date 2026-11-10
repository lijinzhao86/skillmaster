-- The owner's range view counts submitted versions from the audit ledger, one skill at a time.
-- Without this index that query scans every submission in the window across the whole platform;
-- the partial predicate is exactly the one the query names (v60).
CREATE INDEX audit_event_submit_skill_at
  ON audit_event (target_id, at DESC)
  WHERE action = 'submit' AND target_type = 'skill';
