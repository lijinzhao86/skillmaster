package com.skillmasterai.modules.skill;

import java.util.List;

/**
 * The five rungs a skill can be shared at, ordered from the least to the most (v5, ADR 0047).
 *
 * <p><strong>The order is a constant here and not a column.</strong> What the database stores is a
 * rung's name, exactly as it stored {@code viewer} before; what "this rung includes everything
 * below it" means is a rule of the ladder, and a rule belongs in one place the compiler can check.
 * Keeping it in code is also what lets the set of rungs change without a migration that reorders
 * rows.
 *
 * <p><strong>Plain strings rather than an enum, still.</strong> The values are the column's own
 * vocabulary and travel to the browser unchanged, which is the reason the class gave before the
 * ladder — an enum would buy a pair of mapping methods at every boundary. The ladder took away the
 * other reason (that no comparison existed); {@link #atLeast} is that comparison now.
 *
 * <p><strong>{@code owner} is deliberately absent.</strong> A skill's owner is its namespace's
 * owner, which {@code skill.namespace_id} already says, and ownership is not a rung that can be
 * handed out (ADR 0047 §决定 4). Code that reaches "may administer" through ownership asks
 * {@code Permitted#owns()} rather than looking for a {@code "owner"} value here.
 */
public final class GrantRole {

    /** May invoke it and read any file it can name, but cannot list the directory. */
    public static final String INVOKE = "invoke";

    /** The first rung that may read the file list (the detail response's manifest). */
    public static final String BROWSE = "browse";

    /** May submit a version, and discard the drafts they submitted. */
    public static final String WRITE = "write";

    /** May publish (including rollback), and discard anybody's draft. */
    public static final String MAINTAIN = "maintain";

    /** May grant and revoke, and change visibility. Not delete — that is the owner's alone. */
    public static final String ADMIN = "admin";

    /** Low to high. Every rung includes the ones before it. */
    private static final List<String> LADDER =
            List.of(INVOKE, BROWSE, WRITE, MAINTAIN, ADMIN);

    private GrantRole() {
    }

    /** The five grantable rungs, low to high. */
    public static List<String> all() {
        return LADDER;
    }

    /** @return true for exactly the five values the column's CHECK constraint accepts */
    public static boolean isRole(String candidate) {
        return LADDER.contains(candidate);
    }

    /**
     * Whether {@code role} is at or above {@code required}.
     *
     * <p>A null role is below every rung — "no grant and no public rung" is what makes a private
     * skill invisible. Callers that have an owner's standing must say so separately
     * ({@code Permitted#atLeast}); the owner is above this list rather than inside it.
     */
    public static boolean atLeast(String role, String required) {
        if (role == null) {
            return false;
        }
        return rank(role) >= rank(required);
    }

    /** The higher of two rung names, either of which may be null. */
    public static String higher(String a, String b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return rank(a) >= rank(b) ? a : b;
    }

    /** Whether {@code role} is strictly above {@code other}; a null {@code other} is below everything. */
    public static boolean isAbove(String role, String other) {
        return role != null && (other == null || rank(role) > rank(other));
    }

    private static int rank(String role) {
        int index = LADDER.indexOf(role);
        if (index < 0) {
            throw new IllegalArgumentException("not a rung of the ladder: " + role);
        }
        return index;
    }
}
