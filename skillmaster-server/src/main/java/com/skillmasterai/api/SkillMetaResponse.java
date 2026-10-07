package com.skillmasterai.api;

import com.skillmasterai.modules.distribution.SkillDetail;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Level 1 alone: what a client needs in order to decide whether it wants the skill.
 *
 * <p>The Agent Skills standard's first layer is {@code name} and {@code description} — the two fields
 * a host keeps in context for every skill it knows about (§1.1). {@code title} and {@code visibility}
 * come along because the detail endpoint carries them and they cost nothing, and {@code frontmatter}
 * because it may hold fields the standard does not define but the author's host does (a
 * {@code when_to_use}, say).
 *
 * <p><strong>No {@code files}, and no {@code resources} key at all</strong> — not an empty array.
 * An empty list would read as "this skill has no files", which is a different and false statement
 * about a skill that simply was not asked for its manifest. A caller that wants the manifest has the
 * detail endpoint, which is where the pinned per-file URIs are minted.
 *
 * <p>Why this exists beside that endpoint rather than as a flag on it: the manifest is the pivot of
 * the whole read design — it is how a client enumerates L3 without downloading anything (§4.2) — and
 * making it optional would add a round trip to the main flow to save one on the marginal one. So the
 * cheap question got its own address instead.
 *
 * @param version the resolved version, named the way its author named it and identified by its digest
 *                — the two halves a client needs to pin, and the digest is the half that is always
 *                there (ADR 0033)
 */
public record SkillMetaResponse(
        Namespace namespace,
        String name,
        String title,
        String description,
        String visibility,
        JsonNode frontmatter,
        Version version) {

    public record Namespace(String slug, String title) {
    }

    /** @param name the author's version name, or null when they declared none (ADR 0033) */
    public record Version(String name, String digest) {
    }

    public static SkillMetaResponse of(SkillDetail detail, ObjectMapper objectMapper) {
        return new SkillMetaResponse(
                new Namespace(detail.namespaceSlug(), detail.namespaceTitle()),
                detail.name(),
                detail.title(),
                detail.description(),
                detail.visibility(),
                objectMapper.readTree(detail.frontmatterJson()),
                new Version(detail.version(), "sha256:" + detail.digest()));
    }
}
