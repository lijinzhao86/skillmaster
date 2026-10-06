package com.skillmasterai.modules.version;

/**
 * The three states a version can be in (ADR 0031), spelled the way the column spells them.
 *
 * <p>Plain strings rather than an enum, matching how {@code visibility} is handled: the values are
 * the column's own vocabulary, they travel to the browser unchanged, and an enum would buy nothing
 * but a pair of mapping methods at every boundary.
 *
 * <p>The transitions are one-way and few. {@link #DRAFT} is where every version starts, and it
 * leaves for exactly one of the other two — never back, and never between them. "Current" is not a
 * state: it is which published version the skill points at, and a superseded version is still
 * {@link #PUBLISHED}.
 */
public final class VersionState {

    /** Submitted, and not published. Invisible to the consumption plane. */
    public static final String DRAFT = "draft";

    /** Has been published at least once. The pointer may or may not still name it. */
    public static final String PUBLISHED = "published";

    /**
     * Thrown away before ever being published. Terminal, and gone from the consumption plane.
     *
     * <p><strong>Not unreadable, though.</strong> The author plane resolves a discarded version like
     * any other — the picker offers them under 历史 so a person can check what they threw away, and
     * {@code README.md} of a discarded version is still theirs to read. What is closed is publishing
     * it: {@code publishVersion} refuses one outright, so a discarded version can never become what
     * consumers get.
     */
    public static final String DISCARDED = "discarded";

    private VersionState() {
    }
}
