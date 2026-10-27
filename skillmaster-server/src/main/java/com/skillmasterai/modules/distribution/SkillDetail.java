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
 * @param version the version's name, or null when its author declared none (ADR 0033). It is what
 *                the per-file URIs pin when there is one; the digest is the fallback, and
 *                {@code SkillSnapshot#addressSuffix} is where that choice is made
 * @param publishedAt when this version went live. Named for the question this side asks rather than
 *                    for the column it comes from: everything on the consumption plane has been
 *                    published, so "when was it published" is the only thing the timestamp can mean
 *                    here — and it is what this field has always meant. A superseded version reports
 *                    the moment it was first published, which is why it does not move when somebody
 *                    rolls back to it
 * @param isLatest whether this is the version the skill currently points at — false whenever the
 *                 address pinned an older one
 * @param writable whether the caller's <em>account</em> may write this skill — the namespace owner,
 *                 or a grant at {@code write} and above (ADR 0034/0047). <strong>Deliberately not
 *                 the whole answer</strong>: what a credential can actually do is this intersected
 *                 with the scopes it carries, and a scope is the API plane's fact rather than this
 *                 module's. Keeping the account's half here and the credential's half there is what
 *                 stops the two from being computed twice and drifting — the answer shown and the
 *                 answer enforced come from the same predicate
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
        String version,
        String digest,
        String publishedAt,
        int fileCount,
        long totalBytes,
        boolean isLatest,
        boolean writable,
        // v23: what the caller's rung is on this skill, and the actions it unlocks. The CLI prints
        // them as the header of `skill invoke`, so they travel with the same read that resolved
        // `writable` rather than through a second lookup on the client.
        String access,
        List<String> permissions,
        List<File> files) {

    public SkillDetail {
        files = List.copyOf(files);
        permissions = List.copyOf(permissions);
    }

    /**
     * @param sha256Hex lowercase hex; the API adds the {@code sha256:} prefix when presenting it
     */
    public record File(String relpath, String sha256Hex, long size, boolean isBinary) {
    }
}
