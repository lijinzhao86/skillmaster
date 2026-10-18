package com.skillmasterai.modules.skill;

/**
 * The draft-side facts a list needs, computed by M7 from {@code skill_version} and written to
 * {@code skill} by M12.
 *
 * <p><strong>M7 computes it because M12 may not read the version table.</strong> After ADR 0053 a
 * statement may name only one module's tables, so the count and the newest draft cannot be derived
 * where they are stored: they travel through this record and M12 writes them into the projection
 * columns (V21). The alternative — M12 joining {@code skill_version} on every submission — would
 * be the cross-module query the split exists to prevent.
 *
 * <p><strong>The newest fields come from one row.</strong> They are read in a single aggregate pass
 * ordered by {@code number}, so a card can never pair one draft's version name with another's
 * digest, and two submissions in the same second cannot tie.
 *
 * @param count how many versions are still drafts
 * @param newestSubmittedAt RFC3339 UTC of the newest draft, or null when there is none
 * @param newestVersion its name, which may be null (ADR 0033) — the digest never is
 * @param newestDigest its digest
 * @param newestTitle its title
 * @param newestDescription its description
 */
public record DraftProjection(int count, String newestSubmittedAt, String newestVersion,
        String newestDigest, String newestTitle, String newestDescription) {

    /** No drafts: every column becomes null, which is how the list reads "nothing waiting". */
    public static DraftProjection empty() {
        return new DraftProjection(0, null, null, null, null, null);
    }
}
