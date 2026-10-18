package com.skillmasterai.modules.version;

/**
 * One row of a skill's version list, without the manifest.
 *
 * <p>The shape a person chooses between versions with, which is why it carries {@code isCurrent} and
 * none of the metadata: the question it answers is "which of these do I want", not "what is in this
 * one". Reading one is {@link SkillSnapshot}'s job.
 *
 * @param version   the version's name, or null when its author declared none (ADR 0033). A caller
 *                  renders the two differently — the name when there is one, and the digest's short
 *                  form when there is not, the way a repository host shows a short commit hash
 * @param digest    the version's identity, and the only thing a nameless version can be addressed by
 * @param state     draft, published or discarded — what the author has and has not decided about it
 * @param stateAt   when it left draft. Null while it is a draft, which is every version that has just
 *                  been submitted
 * @param title     the version's own title, as submitted. The author page's 版本 table shows it as
 *                  the row's note — it is what the author said that version was about
 * @param submittedBy the account that submitted it, still an id — turning it into a handle is M1's,
 *                  one layer up, where {@code app_user} can be read. Carried because the author
 *                  page says who submitted a version, and because 「写入」 may discard only their
 *                  own draft: both are questions about this person, asked on the version's row
 * @param isCurrent whether the skill's pointer names it, which is the one consumers get. False for a
 *                  superseded version even though that version is still published and still
 *                  addressable by its own name
 */
public record VersionSummary(String version, String digest, String title, int fileCount,
        long totalBytes, String submittedAt, String submittedBy, String state, String stateAt,
        boolean isCurrent) {
}
