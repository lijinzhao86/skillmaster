package com.skillmasterai.modules.skill;

/**
 * The skill-level facts a publish carries, separate from the files.
 *
 * <p>{@code frontmatterJson} is the whole parsed frontmatter as JSON, not just the fields we
 * understand: §3.3 requires unknown fields to be stored and passed through, which is how the
 * baseline's habit of dropping them is avoided. It is supplied as a string rather than as a map so
 * that M7 needs no opinion about serialisation.
 *
 * <p>Note what is absent: {@code visibility}. Metadata changes do not travel with a publish — §4.3
 * gives them their own endpoint, and a republish silently resetting visibility would be a way to
 * make a private skill public by accident.
 *
 * <p><strong>No version name.</strong> Since v6 the number is assigned at publish, not declared in
 * {@code SKILL.md} (ADR 0055 revises ADR 0033), so it is not part of what a submission carries.
 * The frontmatter still travels verbatim in {@code frontmatterJson}, which is a different promise.
 */
public record SkillMetadata(String name, String title, String description,
        String frontmatterJson) {
}
