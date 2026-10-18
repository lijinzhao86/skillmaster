package com.skillmasterai.modules.skill;

/**
 * The facts about a version that become current when it is published.
 *
 * <p>M7 reads them from the version row it is about to make current and hands them to M12's
 * {@code recordPublish}, which writes the pointer and the projection columns in one statement
 * (V21). The values are passed rather than read, because M12 may not name {@code skill_version} —
 * the only cross-table read this split allows is the one-off backfill inside V21 itself.
 *
 * @param versionId the version the pointer will name
 * @param version the version's name, or null when its author declared none (ADR 0033)
 * @param digest its digest
 * @param submittedAt RFC3339 UTC — when it was submitted, not when it went live. This is the
 *                    time the list's "更新于" shows
 * @param title its own title, which becomes {@code skill.title} while it is current
 * @param description its own description, likewise
 * @param frontmatterJson its whole frontmatter, from which {@code when_to_use} is extracted
 */
public record VersionProjection(String versionId, String version, String digest,
        String submittedAt, String title, String description, String frontmatterJson) {
}
