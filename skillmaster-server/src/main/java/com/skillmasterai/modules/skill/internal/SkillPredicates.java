package com.skillmasterai.modules.skill.internal;

import com.skillmasterai.modules.skill.GrantRole;
import java.util.List;

/**
 * The one definition of "may see", "may find", "is in my library" and "does that entry work" (v14,
 * ADR 0082).
 *
 * <p><strong>One shape: a CTE that works out the caller's identity on each row, then filters on
 * it.</strong> The predicates used to be written out three times — the single-row lookup, the
 * consumer list, the author list — and a fourth copy for the library was the point at which the
 * four would start to disagree. {@link #IDENTIFIED} computes the two facts every caller needs
 * ({@code source} and {@code grant_role}) once; the constants below are the questions asked of
 * those facts.
 *
 * <p>Every statement that uses these binds two parameters, {@code :ownNamespaceId} and
 * {@code :callerId}; the ones that use {@link #ACTIVE} also bind {@code :writeAndUp}. The array is
 * built from {@link GrantRole} rather than typed out, so a change to the ladder reaches the SQL.
 *
 * <p>Nothing here names another module's tables: {@code skill}, {@code skill_grant},
 * {@code skill_library_join} and their columns are all M12's. That is what lets the access rule sit
 * inside the statement instead of being applied to its results afterwards.
 */
public final class SkillPredicates {

    /** The rungs at or above {@code write}: a shared entry at these keeps working while offline. */
    public static final List<String> WRITE_AND_UP =
            List.of(GrantRole.WRITE, GrantRole.MAINTAIN, GrantRole.ADMIN);

    /**
     * The rungs at or above {@code maintain}: the ones that may publish. The publish queue asks for
     * these and nothing below, because publishing is what it is a queue for (ADR 0090 decision 7).
     * Ownership is above the ladder rather than on it (ADR 0047 §决定 4), so a query that uses this
     * must ask {@code namespace_id} separately.
     */
    public static final List<String> MAINTAIN_AND_UP =
            List.of(GrantRole.MAINTAIN, GrantRole.ADMIN);

    /**
     * The caller's relationship to each skill, computed once.
     *
     * <p>{@code source} is null when there is none — a public skill the caller has never added,
     * granted or created. That is a fact, not an error: it is what the discovery page asks for.
     * The priority is fixed here (created over shared over joined) so a listing and a filter can
     * never compute two different answers for the same row.
     */
    public static final String IDENTIFIED = """
            WITH identified AS (
                SELECT s.*,
                       g.role AS grant_role,
                       CASE WHEN s.namespace_id = :ownNamespaceId THEN 'created'
                            WHEN g.role IS NOT NULL THEN 'shared'
                            WHEN j.skill_id IS NOT NULL THEN 'joined'
                       END AS source
                  FROM skill s
                  LEFT JOIN skill_grant g
                         ON g.skill_id = s.id AND g.grantee_id = :callerId
                  LEFT JOIN skill_library_join j
                         ON j.skill_id = s.id AND j.user_id = :callerId
            )
            """;

    /**
     * What the discovery plane may serve: public, published, online, not deleted (ADR 0082).
     *
     * <p>Four conditions, each one a different refusal — private, never published, offline, deleted
     * — and every one of them is a reason a result would be unopenable if it leaked in.
     */
    public static final String DISCOVERABLE = """
            s.deleted_at IS NULL
              AND s.current_version_id IS NOT NULL
              AND s.offline_at IS NULL
              AND s.visibility = 'public'
            """;

    /**
     * Whether the caller may see this skill at all: their own, granted to them, or public.
     *
     * <p>Deletion is checked first and on every branch — a soft-deleted skill is invisible even to
     * its owner on this plane (restoring has its own query, which deliberately does the opposite).
     */
    public static final String VISIBLE = """
            s.deleted_at IS NULL
              AND (s.namespace_id = :ownNamespaceId
                   OR s.grant_role IS NOT NULL
                   OR s.visibility = 'public')
            """;

    /**
     * Whether a library entry is effective — the "在用的" half of the library list (ADR 0082).
     *
     * <p>Three answers, one per source. Created and write-level shared entries are the author's
     * working set: a draft or an offline copy is still theirs to work on. Everything else is a
     * consumption entry: it is effective only while there is something to consume (a current
     * version, online) and only while the caller may still see it (public, or still granted). The
     * last clause is what makes a joined skill that went private sleep instead of lingering.
     *
     * <p>Expressed as a CASE rather than a disjunction so each branch reads as its own rule; a
     * single OR would mix "mine" and "shared at write" into a condition nobody could check against
     * the product.
     */
    public static final String ACTIVE = """
            (CASE WHEN s.source = 'created' THEN s.deleted_at IS NULL
                  WHEN s.source = 'shared' AND s.grant_role = ANY(:writeAndUp)
                       THEN s.deleted_at IS NULL
                  ELSE s.deleted_at IS NULL
                       AND s.current_version_id IS NOT NULL
                       AND s.offline_at IS NULL
                       AND (s.visibility = 'public' OR s.grant_role IS NOT NULL)
             END)
            """;

    /**
     * The list key the product shows as "更新于" (TD §谓词).
     *
     * <p>The fallback to {@code created_at} is not cosmetic: a skill whose every version was
     * discarded has neither a submission time nor a draft time, and both the keyset and the card
     * sort key treat a null as a value — one throws, the other silently drops the row.
     */
    public static final String LIST_UPDATED_AT = """
            COALESCE(s.current_version_submitted_at, s.newest_draft_submitted_at, s.created_at)
            """;

    private SkillPredicates() {
    }
}
