package com.skillmasterai.api;

import com.skillmasterai.modules.version.SkillSnapshot;
import com.skillmasterai.modules.version.VersionSummary;
import com.skillmasterai.usecase.model.AuthoredSkill;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The author's view of one skill: the version the address named, every version it has, and that
 * version's manifest.
 *
 * <p>Deliberately shaped like {@link SkillDetailResponse} rather than sharing it. The consumption
 * plane and this one answer different questions about the same rows, and the fields that differ are
 * exactly the ones ADR 0031 introduced: {@code state}, {@code submittedAt}, {@code isCurrent} — plus
 * {@code versions}, which exists only here. A shared record with half its fields null on each plane
 * would make every client branch on which endpoint it called.
 *
 * <p>What is the same is the metadata rule: {@code title}, {@code description} and
 * {@code frontmatter} describe <strong>the version the address named</strong>, not the skill. On this
 * plane that is at its most visible — a draft's metadata is what the author is about to publish, and
 * reading it should not show the description of whatever happens to be live.
 *
 * <p>{@code version} repeats one row of {@code versions}, which is worth the duplication: the page
 * needs the selected version's state to decide whether it may offer a publish button, and having it
 * beside the content it describes keeps that decision out of a search through the list. The two
 * cannot disagree — both are built from rows read in one transaction.
 */
public record AuthoredSkillResponse(
        Namespace namespace,
        String name,
        String title,
        String description,
        String visibility,
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
     * @param state     draft, published or discarded
     * @param stateAt   when it left draft — first published, or discarded. Null while it is a draft
     * @param isCurrent whether the pointer names it, which is what consumers are being served. A
     *                  published version can be false: it was superseded, and its own number still
     *                  resolves
     */
    public record Version(int number, String digest, String submittedAt, String state,
            String stateAt, boolean isCurrent, int fileCount, long totalBytes) {
    }

    public record File(String relpath, String sha256, long size, boolean isBinary) {
    }

    /** @param body the address of the {@code SKILL.md} bytes for the version this response is about */
    public record Resources(String body) {
    }

    public static AuthoredSkillResponse of(AuthoredSkill authored, ObjectMapper objectMapper) {
        SkillSnapshot selected = authored.selected();
        VersionSummary row = authored.versions().stream()
                .filter(summary -> summary.number() == selected.number())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("skill " + selected.skillId()
                        + " resolved version " + selected.number() + ", which its version list"
                        + " does not contain"));

        return new AuthoredSkillResponse(
                new Namespace(authored.namespaceSlug()),
                selected.name(),
                selected.title(),
                selected.description(),
                selected.visibility(),
                objectMapper.readTree(selected.frontmatterJson()),
                version(row),
                authored.versions().stream().map(AuthoredSkillResponse::version).toList(),
                selected.manifest().entries().stream()
                        .map(entry -> new File(entry.relpath(), "sha256:" + entry.blobSha256(),
                                entry.size(), entry.isBinary()))
                        .toList(),
                new Resources(WebSkillRoutes.bodyAt(authored.namespaceSlug(), selected.name(),
                        selected.number())));
    }

    private static Version version(VersionSummary summary) {
        return new Version(summary.number(), "sha256:" + summary.digest(), summary.submittedAt(),
                summary.state(), summary.stateAt(), summary.isCurrent(), summary.fileCount(),
                summary.totalBytes());
    }
}
