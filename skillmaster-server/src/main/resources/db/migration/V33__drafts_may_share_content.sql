-- Drafts may share content with any row of the same skill (ADR 0135).
--
-- A draft is "the one being edited", not an address: `draft create` copies a version
-- verbatim, so the new row's digest equals its base's by design. Published / discarded /
-- revoked rows keep the content-addressed identity the rest of the product relies on
-- (ADR 0005, ADR 0012), so uniqueness stays for them.
ALTER TABLE skill_version
  DROP CONSTRAINT IF EXISTS skill_version_skill_id_digest_key;

CREATE UNIQUE INDEX uq_skill_version_content_nondraft
  ON skill_version (skill_id, digest)
  WHERE state <> 'draft';
