package com.skillmasterai.modules.distribution;

import java.util.List;

/**
 * §4.2's detail shape, filled in: the whole manifest and <strong>none of the content</strong>.
 *
 * <p>This object is the pivot of the whole design. It exists so an agent can decide whether a skill
 * contains what it needs without downloading any of it — the manifest enumerates every file, and
 * the body and each file are separate requests. That is the same progressive loading the MCP
 * extension describes (§1.3), and it is why {@code files} carries sizes and digests but the only
 * bytes in this module are the ones {@link SkillDistributionService#bodyOf} and
 * {@link SkillDistributionService#fileOf} are explicitly asked for.
 *
 * @param namespaceSlug the namespace the skill lives in, already known to be readable by the caller
 * @param frontmatterJson the frontmatter exactly as published, unknown fields included
 * @param number the version's immutable alias (ADR 0012). It is what the per-file URIs pin, because
 *               it is the cheapest thing a client can carry forward and it is as immutable as the
 *               digest
 * @param publishedAt when this version went live. Named for the question this side asks rather than
 *                    for the column it comes from: everything on the consumption plane has been
 *                    published, so "when was it published" is the only thing the timestamp can mean
 *                    here — and it is what this field has always meant. A superseded version reports
 *                    the moment it was first published, which is why it does not move when somebody
 *                    rolls back to it
 * @param isLatest whether this is the version the skill currently points at — false whenever the
 *                 address pinned an older one
 */
public record SkillDetail(
        String id,
        String name,
        String title,
        String description,
        String namespaceSlug,
        String namespaceTitle,
        String visibility,
        String frontmatterJson,
        int number,
        String digest,
        String publishedAt,
        int fileCount,
        long totalBytes,
        boolean isLatest,
        List<File> files) {

    public SkillDetail {
        files = List.copyOf(files);
    }

    /**
     * @param sha256Hex lowercase hex; the API adds the {@code sha256:} prefix when presenting it
     */
    public record File(String relpath, String sha256Hex, long size, boolean isBinary) {
    }
}
