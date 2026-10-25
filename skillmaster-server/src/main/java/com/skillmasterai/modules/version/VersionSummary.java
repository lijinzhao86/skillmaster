package com.skillmasterai.modules.version;

/**
 * One row of a skill's version list, without the manifest.
 *
 * <p>The shape a person chooses between versions with, which is why it carries {@code isCurrent} and
 * none of the metadata: the question it answers is "which of these do I want", not "what is in this
 * one". Reading one is {@link SkillSnapshot}'s job.
 *
 * @param id        the row's id, which is what a {@code draft:<id>} pin is built from. Present on
 *                  every row; only drafts need it, but hiding it on published rows would make the
 *                  field depend on {@code state} for no gain
 * @param version   the version's name, or null when its author declared none (ADR 0033). A caller
 *                  renders the two differently — the name when there is one, and the digest's short
 *                  form when there is not, the way a repository host shows a short commit hash
 * @param draftName the author's label, set at submission and frozen at publish (ADR 0055). Null on
 *                  rows submitted before v6; the plane then falls back to the version number
 * @param note      the one-line note the author gave this version ({@code changelog}), frozen once
 *                  published. Empty string reads as no note
 * @param digest    the version's identity, and the only thing a nameless version can be addressed by
 * @param state     draft, published, revoked or discarded — what the author has and has not decided
 *                  about it (ADR 0031, ADR 0058)
 * @param stateAt   when it last changed state. Null while it is a draft, which is every version that
 *                  has just been submitted
 * @param firstPublishedAt when it first went live. Never moves; {@code stateAt} does, so the
 *                  consumer plane's "published at" reads this one
 * @param revokedReason the maintain's optional note, only on a revoked row (ADR 0059)
 * @param title     the version's own title, as submitted. The author page's 版本 table shows it as
 *                  the row's note — it is what the author said that version was about
 * @param submittedBy the account that submitted it, still an id — turning it into a handle is M1's,
 *                  one layer up, where {@code app_user} can be read. Carried because the author
 *                  page says who submitted a version, and because 「写入」 may discard only their
 *                  own draft: both are questions about this person, asked on the version's row
 * @param isCurrent whether the skill's pointer names it, which is the one consumers get. False for a
 *                  superseded version even though that version is still published and still
 *                  addressable by its own name
 * @param releaseType how big the change is, as the draft declares it ({@code major} / {@code minor}
 *                  / {@code patch}), or null when it declares none. An input to the publish-time
 *                  computation, not a number: see ADR 0091
 */
public record VersionSummary(String id, String version, String draftName, String note,
        String digest, String title, int fileCount, long totalBytes, String submittedAt,
        String submittedBy, String state, String stateAt, String firstPublishedAt,
        String revokedReason, boolean isCurrent, String releaseType) {

    public VersionSummary {
        // The column is NOT NULL and clearing a note stores the empty string; on the wire an absent
        // note is null (TD §草稿说明). Normalising once here means every response that reads a row
        // agrees, instead of each mapping deciding for itself.
        note = note == null || note.isBlank() ? null : note;
    }

    /** The five-value read-model state (§派生状态): derived, never stored. */
    public String status() {
        return VersionStatus.of(state, isCurrent);
    }
}
