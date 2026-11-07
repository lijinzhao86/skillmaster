-- v51 follow-up: the executable bit is a property of the path, not of the file.
--
-- `scripts/` is the one surface this platform executes, so everything under it is executable and
-- everything else is not — whatever the file's mode was on the machine it came from, and however
-- it was created (upload, sync, console editor). This backfills the rows V40 filled with the
-- default, so a version published before the rule existed serves the same modes as one published
-- after it. The digest is over content only and does not move: nothing here changes bytes.
UPDATE version_file
   SET mode = CASE WHEN relpath LIKE 'scripts/%' THEN 493 ELSE 420 END
 WHERE mode <> CASE WHEN relpath LIKE 'scripts/%' THEN 493 ELSE 420 END;
