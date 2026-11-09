package com.skillmasterai.modules.skill;

/**
 * Where a skill came from, when it was produced by copying another skill on this platform.
 *
 * <p><strong>A snapshot, not a pointer.</strong> The source may be renamed, taken down or deleted
 * afterwards; this row keeps the identities and the address as they read at the moment of the copy.
 * That is what makes the information page's 「复制自」 true a year later.
 *
 * <p><strong>Skill-level.</strong> A copy produces exactly one new skill and one first draft, so
 * the row is keyed by the skill. Later versions of the copy do not each get a row: they are
 * versions of the copy, not further copies.
 *
 * @param versionId the first draft the copy created — the version the source's content became
 */
public record SkillCopy(String skillId, String versionId, String sourceSkillId,
        String sourceVersionId, String sourceAddress, String sourceVersionLabel,
        String copiedBy, String copiedAt) {
}
