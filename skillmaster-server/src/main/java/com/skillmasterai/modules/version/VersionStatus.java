package com.skillmasterai.modules.version;

/**
 * The five states a reader sees, derived from the stored state and whether the pointer names it.
 *
 * <p>Stored there are four values — {@code state} — and "current" is not a state at all: it is which
 * published row the skill points at. The page, the filter chips and any analysis ask a different
 * question ("is this live, history, revoked…"), and answering it in every client would put the same
 * join in five places. So the read model translates once (ADR 0058): the result is not stored, has
 * no column, and never reaches a write path.
 *
 * <p>Plain strings for the same reason {@link VersionState}'s values are: they travel to the
 * browser unchanged.
 */
public final class VersionStatus {

    /** Submitted, not published. */
    public static final String DRAFT = "draft";

    /** Published, and the one consumers get by the bare address. */
    public static final String LIVE = "live";

    /** Published, no longer current, still readable by its own pin. */
    public static final String HISTORY = "history";

    /** Taken down by a maintain. The consumer plane answers 410. */
    public static final String REVOKED = "revoked";

    /** Thrown away before it was ever published. */
    public static final String DISCARDED = "discarded";

    private VersionStatus() {
    }

    /**
     * @param state     the row's stored state
     * @param isCurrent whether the skill's pointer names it
     * @throws IllegalArgumentException when the state is none of the four. That would be a row the
     *         schema's {@code skill_version_state_known} constraint should have refused, so it is
     *         loud rather than mapped to some default
     */
    public static String of(String state, boolean isCurrent) {
        return switch (state) {
            case VersionState.DRAFT -> DRAFT;
            case VersionState.PUBLISHED -> isCurrent ? LIVE : HISTORY;
            case VersionState.REVOKED -> REVOKED;
            case VersionState.DISCARDED -> DISCARDED;
            default -> throw new IllegalArgumentException("unknown version state: " + state);
        };
    }
}
