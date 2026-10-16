-- Where a first-party CLI login was approved from. v4.
--
-- **A column, and the reason is that the value exists for one instant.** The console shows a login's
-- address on its row (the design's arrangement: the upper line is the full address, the lower one says
-- what device, when it signed in and when it was last active). For a browser session that address is
-- the session's own, which Spring Session already stores. For a CLI login it is the address of the
-- *console visitor who approved it*, and after that request returns there is nothing left holding it.
--
-- **Not read out of the audit trail**, which would otherwise be the obvious home for "where this came
-- from": the mint already records an event, but `audit_event` belongs to M10 and
-- `TableOwnershipTest` fails the build over a query that names another module's table. A fact a screen
-- shows on every render belongs on the row it is about anyway — the same reasoning `api_key`'s
-- `last_used_from` records for the other credential.
--
-- **Could be null, and that is a real answer.** A framework-written authorization has no approver
-- address; so does a login whose socket the container did not expose. The console draws the line it
-- can and nothing more, in the shape the session screen already uses for a row with no device.
ALTER TABLE oauth_authorization ADD COLUMN address TEXT;

COMMENT ON COLUMN oauth_authorization.address IS
  'Where the CLI login was approved from — the console visitor''s address at the moment of the click, not the CLI''s (it has none). NULL for third-party authorizations and for a request whose socket was not exposed. Not a credential and not a claim about where the machine is.';
