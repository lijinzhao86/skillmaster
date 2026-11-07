-- v50: a snapshot row exists both for the sync's drafts and for the refresh of a published version.
-- The publish path must keep a sync-written origin when the version is promoted, but must not treat
-- a merely refreshed version as one the sync wrote. Existence alone cannot tell the two apart, so the
-- writer records which one it was. Refresh never clears the mark: it is a fact about the version.
-- No backfill: v50 has never run in production, so there are no sync snapshots older than this
-- column. A pre-release database that ran an earlier v50 build can be reset, or will re-download
-- each entry once and mark the version it replays into.
ALTER TABLE skill_source_snapshot
  ADD COLUMN written_by_sync BOOLEAN NOT NULL DEFAULT FALSE;
