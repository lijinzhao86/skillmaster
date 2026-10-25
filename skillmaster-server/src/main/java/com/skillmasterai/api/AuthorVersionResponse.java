package com.skillmasterai.api;

import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.version.VersionSummary;
import java.util.List;
import java.util.Map;

/**
 * The author API's version object and its two envelopes (v6).
 *
 * <p>Deliberately its own shape rather than a reuse of {@code AuthoredSkillResponse}: that one is
 * the browser page's resource, which carries the skill's metadata and every version at once. This is
 * a CLI's contract, paged and stable, and the two would drift the moment one gained a field for its
 * own page.
 *
 * <p>Field names are snake_case on the wire through the global strategy, as everywhere else.
 */
public record AuthorVersionResponse(Skill skill, Version version) {

    /** @param address {@code ns/name}, which is what every command's argument spells */
    public record Skill(String namespace, String name, String address) {
    }

    /**
     * One version's facts.
     *
     * @param pin     the address suffix that names this row: its own id (the version code) for a
     *                draft or discarded row, the version number for a published one,
     *                {@code sha256:…} for a nameless one
     * @param code    this row's id — an eight-character short code, or a 26-character ULID on a v6
     *                row. Shown as 「版本码」; never used to address a version on the consumption
     *                plane, and on the author plane it is the same string {@code pin} carries for
     *                a draft or discarded row
     * @param name    the author's label (ADR 0055); null on rows from before v6
     * @param version the version number, or null for a draft
     * @param status  the derived five-value status
     * @param note    the one-line note, or null
     * @param digest  prefixed with {@code sha256:}, as every digest on the wire is
     * @param files   file count
     * @param bytes   total bytes
     */
    public record Version(String address, String pin, String code, String name, String version,
            String status, String note, String submittedBy, String submittedAt,
            String firstPublishedAt, String stateAt, String revokedReason, String digest,
            int files, long bytes, String releaseType) {
    }

    public static AuthorVersionResponse of(String namespaceSlug, Access access,
            VersionSummary summary, Map<String, String> handles) {
        return new AuthorVersionResponse(
                new Skill(namespaceSlug, access.name(), namespaceSlug + "/" + access.name()),
                view(namespaceSlug, access, summary, handles));
    }

    public static Version view(String namespaceSlug, Access access, VersionSummary summary,
            Map<String, String> handles) {
        // A discarded row never had a number — only drafts can be discarded — and its stable
        // address stays the row id, the one it was written and read by. Driving this off the
        // state rather than "has a number" keeps the row's address the same before and after.
        // Since ADR 0063 the spelling is bare: the row id *is* the suffix, with no state prefix.
        boolean rowAddressed = "draft".equals(summary.state())
                || "discarded".equals(summary.state());
        String pin = summary.version() != null
                ? summary.version()
                : summary.id() != null && rowAddressed
                        ? summary.id()
                        : "sha256:" + summary.digest();
        return new Version(
                namespaceSlug + "/" + access.name(),
                pin,
                summary.id(),
                summary.draftName(),
                summary.version(),
                summary.status(),
                summary.note(),
                handles.get(summary.submittedBy()),
                summary.submittedAt(),
                summary.firstPublishedAt(),
                summary.stateAt(),
                summary.revokedReason(),
                "sha256:" + summary.digest(),
                summary.fileCount(),
                summary.totalBytes(),
                summary.releaseType());
    }

    /** The list envelope: the skill, a page of versions, the unfiltered counts, the next cursor. */
    public record Page(Skill skill, List<Version> versions, Map<String, Integer> counts,
            String next) {
    }

    /** A write's answer: what the row reads now. */
    public record Written(boolean ok, Version version) {
    }
}
