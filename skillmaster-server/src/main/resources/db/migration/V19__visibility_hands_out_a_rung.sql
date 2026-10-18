-- Visibility hands one rung to everybody. v5, ADR 0048.
--
-- Two states stay two states (ADR 0037): `visibility` still says private | public, and a second
-- column says which rung a stranger gets. A three-value enum would make "private · maintain" a value
-- the database accepts and the product can never mean, and would repeat the same list of pairs three
-- times over in the CHECK.
--
-- No backfill: `private` is the only value that exists today, and every existing row satisfies the
-- new constraint with a NULL `public_rung`.
ALTER TABLE skill ADD COLUMN public_rung TEXT;

ALTER TABLE skill ADD CONSTRAINT skill_public_rung CHECK (
  (visibility = 'private' AND public_rung IS NULL) OR
  (visibility = 'public'  AND public_rung IN ('invoke', 'browse'))
);

-- ADR 0037 removed `unlisted`, and its §后果 asked for these two comments to be corrected by the
-- next migration that really touches the tables. This is that one. `namespace.visibility` itself
-- stays out of v5's scope (there is no product need for namespace-level public yet); only its
-- comment is corrected.
COMMENT ON COLUMN skill.visibility IS 'private | public. See ADR 0037, ADR 0048.';
COMMENT ON COLUMN namespace.visibility IS 'private | public. See ADR 0037, ADR 0048.';

-- No index: `public_rung` is read only from the row whose skill was already resolved.
