package com.skillmasterai.api;

import com.skillmasterai.modules.version.VersionSummary;
import com.skillmasterai.usecase.model.AuthoredSkill;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The author plane's view of one skill, shaped per the caller's rung (ADR 0051).
 *
 * <p><strong>One shape, three amounts.</strong> At {@code invoke} the answer is the skill's L1 and
 * its published versions, with an empty {@code files} and a null {@code resources}; at
 * {@code browse} the file manifest and the body's address join them; at {@code write} drafts appear
 * in {@code versions} as well. The page renders the same skeleton at every rung, and the fields
 * that are absent are the ones the rung does not hand out — not nulls standing in for a failure.
 *
 * <p><strong>{@code version} and {@code resources} are nullable, deliberately.</strong>
 * {@code version} is null when nothing the caller may see resolved — the empty-tree case ADR 0051
 * keeps apart from a 404. {@code resources} is null below {@code browse} even when a version did
 * resolve: a caller who may not read the files is not given an address for the body either.
 *
 * <p><strong>{@code rung} is the caller's own standing</strong> — {@code owner}, or one of the five
 * ladder rungs. The page draws only the sections that standing can reach (ADR 0051), and it can
 * only do that if it is told; the alternative is a probe of every write route for a 403.
 *
 * <p><strong>{@code pendingRung} is the caller's own waiting request</strong> (ADR 0052), or null
 * when nothing is pending. The information page's 「等待处理」 needs it to survive a reload; without
 * it, a page that remembered its own click would show the form again to somebody already waiting.
 *
 * <p><strong>{@code publicRung} is null exactly when {@code visibility} is private</strong>
 * (V19's CHECK makes the pair one state): 设置 › 常规 reads the three-way choice back from here.
 *
 * <p>Deliberately shaped like {@link SkillDetailResponse} rather than sharing it. The consumption
 * plane and this one answer different questions about the same rows, and the fields that differ are
 * exactly the ones ADR 0031 introduced: {@code state}, {@code submittedAt}, {@code isCurrent} — plus
 * {@code versions}, which exists only here. A shared record with half its fields null on each plane
 * would make every client branch on which endpoint it called.
 */
public record AuthoredSkillResponse(
        Namespace namespace,
        String name,
        String title,
        String description,
        String visibility,
        String publicRung,
        String rung,
        String pendingRung,
        JsonNode frontmatter,
        Version version,
        List<Version> versions,
        List<File> files,
        Resources resources) {

    public record Namespace(String slug) {
    }

    /**
     * One version of the skill, and what the author has decided about it.
     *
     * @param name      the version name the author declared, or null when they declared none — in
     *                  which case {@code digest} is what a caller renders and addresses it by
     * @param title     the version's own title, shown as the row's note in 版本
     * @param submittedBy the handle of the account that submitted it, or null when that account is
     *                  no longer resolvable. The page says who submitted a version, and 「写入」 may
     *                  discard only their own draft — so both the row's text and its actions read
     *                  this value
     * @param state     draft, published or discarded
     * @param stateAt   when it left draft — first published, or discarded. Null while it is a draft
     * @param isCurrent whether the pointer names it, which is what consumers are being served. A
     *                  published version can be false: it was superseded, and its own name still
     *                  resolves
     */
    public record Version(String name, String digest, String title, String submittedAt,
            String submittedBy, String state, String stateAt, boolean isCurrent, int fileCount,
            long totalBytes) {
    }

    public record File(String relpath, String sha256, long size, boolean isBinary) {
    }

    /** @param body the address of the {@code SKILL.md} bytes; null below the {@code browse} rung */
    public record Resources(String body) {
    }

    public static AuthoredSkillResponse of(AuthoredSkill authored, ObjectMapper objectMapper) {
        Version selected = authored.selected() == null ? null
                : authored.versions().stream()
                        .filter(summary -> summary.digest().equals(authored.selected().digest()))
                        .findFirst()
                        .map(summary -> version(summary, authored.submitters()))
                        // Matched by digest, the identity, rather than by the version name: a
                        // nameless version has no name to match on (ADR 0033). The two cannot
                        // disagree — both come from rows read in one transaction — so this is
                        // our own corruption rather than a miss.
                        .orElseThrow(() -> new IllegalStateException("skill " + authored.name()
                                + " selected version " + authored.selected().digest()
                                + ", which its version list does not contain"));

        return new AuthoredSkillResponse(
                new Namespace(authored.namespaceSlug()),
                authored.name(),
                authored.title(),
                authored.description(),
                authored.visibility(),
                authored.publicRung(),
                authored.rung(),
                authored.pendingRung(),
                objectMapper.readTree(authored.frontmatterJson()),
                selected,
                authored.versions().stream()
                        .map(summary -> version(summary, authored.submitters()))
                        .toList(),
                authored.filesVisible() && authored.selected() != null
                        ? authored.selected().manifest().entries().stream()
                                .map(entry -> new File(entry.relpath(),
                                        "sha256:" + entry.blobSha256(), entry.size(),
                                        entry.isBinary()))
                                .toList()
                        : List.of(),
                authored.filesVisible() && authored.selected() != null
                        ? new Resources(WebSkillRoutes.bodyAt(authored.namespaceSlug(),
                                authored.name(), authored.selected().addressSuffix()))
                        : null);
    }

    private static Version version(VersionSummary summary, java.util.Map<String, String> submitters) {
        return new Version(summary.version(), "sha256:" + summary.digest(), summary.title(),
                summary.submittedAt(), submitters.get(summary.submittedBy()), summary.state(),
                summary.stateAt(), summary.isCurrent(), summary.fileCount(), summary.totalBytes());
    }
}
