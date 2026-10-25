package com.skillmasterai.modules.version;

/**
 * One waiting draft, as the publish queue needs it.
 *
 * <p>M7's public shape for a draft row in a listing — deliberately not {@link VersionSummary},
 * which answers "which of these versions do I want" for one skill at a time. This one is built for
 * many skills at once and carries only what a queue row shows.
 *
 * @param skillId which skill the draft belongs to. The queue groups by it and never shows it
 * @param code the row's id — the pin, bare, with no {@code draft:} prefix (ADR 0063)
 * @param name the author's label, or null on a row submitted before v6 (the page says「未命名」)
 * @param releaseType {@code major} / {@code minor} / {@code patch}, or null when undeclared (ADR 0091)
 * @param submittedAt RFC3339 UTC
 */
public record PublishDraft(String skillId, String code, String name, String releaseType,
        String submittedAt) {
}
