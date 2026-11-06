-- V36 gave every pre-v49 official version a provisional 'missing' license without reading
-- SKILL.md. The declaration is in the version's own frontmatter, so fill those placeholders in.
-- Only placeholders are touched: a row the source has since answered is left alone.
UPDATE skill_version_license l
SET spdx = declared.value,
    name = declared.value,
    status = 'declared-only',
    detail = jsonb_build_object('declared', declared.value)::text,
    fetched_at = to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
FROM skill_version sv
CROSS JOIN LATERAL (
    SELECT COALESCE(
        CASE WHEN jsonb_typeof(sv.frontmatter::jsonb #> '{metadata,license}') = 'string'
             THEN NULLIF(btrim(sv.frontmatter::jsonb #>> '{metadata,license}'), '') END,
        CASE WHEN jsonb_typeof(sv.frontmatter::jsonb -> 'license') = 'string'
             THEN NULLIF(btrim(sv.frontmatter::jsonb ->> 'license'), '') END
    ) AS value
) AS declared
WHERE l.version_id = sv.id
  AND l.status = 'missing'
  AND l.detail = '{}'
  AND declared.value IS NOT NULL;
