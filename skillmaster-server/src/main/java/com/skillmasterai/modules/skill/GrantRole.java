package com.skillmasterai.modules.skill;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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

    /**
     * What this rung may do, as the words a client shows (v23).
     *
     * <p><strong>Actions, not command names.</strong> The CLI's commands are {@code skill files} and
     * {@code skill write}; the permission is "may list" and "may write". Keeping the two vocabularies
     * apart is what lets a command be renamed without the permission set moving with it.
     *
     * <p><strong>Only what a CLI can do.</strong> {@code maintain} can publish and {@code admin} can
     * change visibility, but both are console actions with no command — listing them would send a
     * client looking for something that does not exist (v23 PRD §不做 2). The owner is reported at
     * {@code admin}: it is above the ladder, and for every action that has a command the two are the
     * same set.
     *
     * <p>This lives beside {@link #LADDER} rather than in the API layer for the same reason the
     * ladder does: a mapping two files apart is a mapping that will drift. {@code GrantRoleTest}
     * asserts that every rung has one and that no value escapes this list.
     */
    public static List<String> permissionsOf(String rung) {
        if (rung == null) {
            return List.of();
        }
        List<String> permissions = new ArrayList<>();
        permissions.add("read");
        if (atLeast(rung, BROWSE)) {
            permissions.add("list");
        }
        if (atLeast(rung, WRITE)) {
            permissions.add("write");
        }
        if (atLeast(rung, MAINTAIN)) {
            permissions.add("discard");
        }
        if (atLeast(rung, ADMIN)) {
            permissions.add("share");
        }
        return List.copyOf(permissions);
    }

    /**
     * One capability and the commands it unlocks (v34, ADR 0119).
     *
     * <p><strong>The command names are part of the contract.</strong> The header's whole point is
     * that the agent can act on it without a lookup, so renaming a command means changing this
     * table in the same change as the CLI (ADR 0119 §后果). The old action words in
     * {@link #permissionsOf} stay beside them for one generation: an older CLI parses that field
     * and would fail outright on a shape it does not know.
     *
     * @param capability a short, plain-language statement of what the caller may do
     *                   ({@code read files}); it is a name, not a sentence to parse
     * @param commands   what to type after {@code skmr} — {@code skill read}, {@code version discard}
     */
    public record Permission(String capability, List<String> commands) {
        public Permission {
            commands = List.copyOf(commands);
        }
    }

    private static final Permission READ_FILES = new Permission("read files",
            List.of("skill invoke", "skill read", "skill cat", "skill head", "skill tail"));
    private static final Permission LIST_FILES = new Permission("list files",
            List.of("skill ls", "skill find", "skill stat", "skill grep", "skill wc", "skill check"));
    private static final Permission WRITE_FILES = new Permission("write files",
            List.of("skill write", "skill edit", "skill rm", "skill mv", "skill cp"));
    private static final Permission DISCARD_DRAFTS = new Permission("discard drafts",
            List.of("version discard"));
    private static final Permission SHARE_THIS_SKILL = new Permission("share this skill",
            List.of("skill share", "skill unshare", "skill shares"));

    /** The capabilities that a credential without {@code skills:write} does not get (ADR 0119 §5). */
    public static final Set<String> WRITE_CAPABILITIES =
            Set.of("write files", "discard drafts", "share this skill");

    /**
     * The rung's capabilities, cumulative like {@link #permissionsOf}: every rung includes the ones
     * below it. {@code discard drafts} starts at {@code maintain} for the reason 0100 §决定 4 gave —
     * a {@code write} caller may discard <em>its own</em> drafts, and the header has no "whose"
     * dimension — which is a known gap, not an oversight.
     */
    public static List<Permission> capabilitiesOf(String rung) {
        if (rung == null) {
            return List.of();
        }
        List<Permission> capabilities = new ArrayList<>();
        capabilities.add(READ_FILES);
        if (atLeast(rung, BROWSE)) {
            capabilities.add(LIST_FILES);
        }
        if (atLeast(rung, WRITE)) {
            capabilities.add(WRITE_FILES);
        }
        if (atLeast(rung, MAINTAIN)) {
            capabilities.add(DISCARD_DRAFTS);
        }
        if (atLeast(rung, ADMIN)) {
            capabilities.add(SHARE_THIS_SKILL);
        }
        return List.copyOf(capabilities);
    }
}
