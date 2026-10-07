package com.skillmasterai.modules.version;

/**
 * The two roles a skill can be shared with (ADR 0034), spelled the way the column spells them.
 *
 * <p>Plain strings rather than an enum, for the reason {@link VersionState} gives and
 * {@code namespace_member.role} already follows: the values are the column's own vocabulary, they
 * travel to the browser unchanged, and an enum would buy a pair of mapping methods at every
 * boundary.
 *
 * <p><strong>The vocabulary is shared with {@code namespace_member} on purpose</strong> — it has
 * carried {@code owner | editor | viewer} since the baseline, writing only {@code owner} so far.
 * Two words for one idea would turn the eventual merge of the two models into a migration.
 * {@code owner} is deliberately absent here: a skill's owner is its namespace's owner, which
 * {@code skill.namespace_id} already says.
 */
public final class GrantRole {

    /** May read the skill: L1, L2 and L3. The narrower of the two. */
    public static final String VIEWER = "viewer";

    /**
     * May read it, and may also submit versions to it and discard the ones they submitted.
     *
     * <p><strong>Not "may publish".</strong> Publishing changes what every reader of this service
     * gets, and ADR 0031 keeps that in the browser, by a person — an editor who could publish would
     * be able to replace the owner's skill and leave them to find out afterwards. Not "may delete"
     * either, and not "may re-share": a grant that could be passed on would spread without the
     * owner seeing any link in the chain (ADR 0034 §理由).
     */
    public static final String EDITOR = "editor";

    private GrantRole() {
    }

    /** @return true for exactly the two values the column's CHECK constraint accepts */
    public static boolean isRole(String candidate) {
        return VIEWER.equals(candidate) || EDITOR.equals(candidate);
    }
}
