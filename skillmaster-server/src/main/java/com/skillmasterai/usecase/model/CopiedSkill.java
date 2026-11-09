package com.skillmasterai.usecase.model;

/**
 * What a copy produced: a new private skill, its first draft, and where it came from.
 *
 * <p>The draft is a real submission (ADR 0070's shape, without publishing): it has an id, a name
 * and content, and the caller edits it exactly as any other draft. {@code version} is null because
 * a version number is assigned at publish (ADR 0055), and a copy never publishes.
 *
 * @param draftId the new draft's short code — the pin a client acts on next
 * @param draftName the label the copy gave the draft (「复制自 <address>」)
 * @param sourceAddress the source as it read at copy time, {@code namespace/name}
 * @param sourceVersion the source version's name or {@code sha256:<digest>}
 */
public record CopiedSkill(
        String skillId,
        String namespaceSlug,
        String name,
        String draftId,
        String draftName,
        String version,
        String sourceAddress,
        String sourceVersion) {
}
