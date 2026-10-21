package com.skillmasterai.modules.ingest;

/**
 * What M5 hands on: the skill's content, plus the version metadata that travelled beside it.
 *
 * <p><strong>The sidecar is separate from the upload on purpose.</strong> {@link SkillUpload} is
 * exactly what becomes a version — its files, their count and their bytes, and the frontmatter that
 * describes them. The metadata is not part of that: it is a declaration about the <em>row</em> the
 * upload will create, and folding it into the upload would put a field into content identity that
 * must never change it (ADR 0069).
 *
 * @param upload  the content, with any sidecar already removed from its file list
 * @param sidecar the version metadata, or {@code null} when the package carried none
 */
public record ValidatedUpload(SkillUpload upload, SkillSidecar sidecar) {
}
