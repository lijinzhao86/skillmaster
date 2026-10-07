-- A version's name is what its author says it is, not what the server counted.
--
-- ADR 0012 made the alias a server-allocated integer: unique, monotonic, and guaranteed by the
-- skill-row lock. It was a good alias and a meaningless name — `@3` says nothing about
-- compatibility, while every other tool in this ecosystem names a version with a semver: the host's
-- own plugin manifest requires one (`1.0` / `v1.0.0` / `latest` are rejected by its loader), and
-- publishers like Feishu ship a `version` in their skill frontmatter. ADR 0033 swaps the alias; the
-- identity is untouched: `digest` is still what a version *is*.
--
-- Nullable, and that is a decision rather than an oversight. An author who declares no version gets
-- a version with no name, addressable only by digest — the same bargain the host's plugin loader
-- makes when `version` is omitted and it falls back to the commit SHA. Forcing a version would make
-- every submission start with a compatibility judgement the author may not want to make.
ALTER TABLE skill_version ADD COLUMN version TEXT;

-- Unique within one skill, which is what makes `@1.2.3` mean one thing for ever. Enforced here
-- rather than in Java because a concurrent pair of submissions must not both win: the insert is
-- what discovers the conflict, and `ON CONFLICT ... DO NOTHING` cannot tell which constraint fired.
-- PostgreSQL treats NULLs as distinct, so any number of nameless versions coexist.
ALTER TABLE skill_version ADD CONSTRAINT skill_version_skill_id_version_key UNIQUE (skill_id, version);

-- No backfill. The semver rule lives in Java (`common/SemVer`); writing it again as a SQL regex
-- would create a second authority for the same rule, and the two would drift. Nothing is deployed —
-- V10's comment makes the same point — so the only rows this leaves nameless are a local
-- development database's. **Re-submitting is not a backfill**: adding `version:` to a SKILL.md
-- changes its bytes, hence its digest, hence inserts a *new* version rather than naming the old one.
-- The consequence is honest and recorded in ADR 0033 §后果: every `@N` address in existence stops
-- resolving, because `@N` is no longer part of the grammar.

COMMENT ON COLUMN skill_version.version IS
  'The version''s name: a semver string the author declared in SKILL.md''s top-level `version:`. Immutable — submitting the same name with different content is refused (ADR 0033). NULL when the author declared none, in which case this version is addressable only by its digest.';
COMMENT ON COLUMN skill_version.number IS
  'Submission order, and only that: the author listing sorts by it, and MAX(number)+1 allocates it under the skill row''s lock. It is no longer a name — it appears in no address and in no response (ADR 0033, the same reasoning that keeps `id` out of URLs in ADR 0004).';
