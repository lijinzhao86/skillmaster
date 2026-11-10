package com.skillmasterai.modules.activity;

import java.util.List;

/**
 * M17's read edge for one person (v58).
 *
 * <p>Everything here is scoped by the account the caller supplies and by a {@link Window}. The
 * retention boundary is still 90 days — a record older than that has been exported and dropped — so
 * a window never reaches past it. The page and its stats are built from the same rows and the same
 * keyword predicate, so their numbers cannot disagree.
 */
public interface UsageHistory {

    /** The default window, and the longest one the retention boundary allows. */
    int WINDOW_DAYS = 90;

    /** One page of the interaction list, newest first. */
    Page interactions(String actorUserId, int page, int limit, Window window);

    /**
     * Whether this account has any row the list would ever show — a search or a call, inside the
     * retention window, the time range and skill keyword aside. Sign-ins and file reads are
     * recorded too but are not list rows. The page uses it to tell "no records yet" from "the
     * filters kept nothing".
     */
    boolean hasAny(String actorUserId);

    /** One account's rows in a window. */
    Stats stats(String actorUserId, Window window);

    /** The owners' per-person calls for one skill, before handles and roles are resolved. */
    List<ActorInvokes> collaboratorDays(String skillId, Window window);

    /** One skill's exact totals in a window, for the public owner view before bucketing. */
    PublicRange skillRange(String skillId, Window window);

    /** One person's use of one skill, split by version. */
    Mine mine(String skillId, String actorUserId, Window window);

    /**
     * A half-open time window, {@code at >= since AND at < until}, plus an optional skill keyword.
     *
     * <p><strong>Instants, not dates.</strong> "Local calendar day" is the client's fact: only it
     * knows the timezone, so it converts the chosen days to two instants and the server never
     * parses a date or a preset name. {@code skill} is an address keyword (case-insensitive
     * contains), not a skill identity — one keyword can match several addresses.
     */
    record Window(String since, String until, String skill) {

        /** The effective span in whole days: DST moves it by an hour, rounding removes that. */
        public int days() {
            long seconds = java.time.Duration.between(
                    java.time.Instant.parse(since), java.time.Instant.parse(until)).getSeconds();
            return Math.max(1, (int) Math.round(seconds / 86_400.0));
        }
    }

    record Page(List<Interaction> items, long total, int page, int pageSize) {
        public Page {
            items = List.copyOf(items);
        }
    }

    /** A search row and a direct call, told apart by shape rather than by a nullable field. */
    sealed interface Interaction permits Search, Direct {
    }

    /**
     * @param total       the response total, or null when the API's search response had none
     * @param invocations every call attributed to this search, oldest first; may be empty
     */
    record Search(long id, String at, String query, Long total, int returned, List<Result> results,
            List<Invocation> invocations) implements Interaction {
        public Search {
            results = List.copyOf(results);
            invocations = List.copyOf(invocations);
        }
    }

    record Direct(long id, String at, String skill, String version) implements Interaction {
    }

    record Result(String skill, int rank) {
    }

    record Invocation(String skill, String version, String at, int rank) {
    }

    record Stats(long calls, long searches, long converted, long missed, long skills,
            String latest, List<TopSkill> top) {
        public Stats {
            top = List.copyOf(top);
        }
    }

    record TopSkill(String skill, long calls, String last) {
    }

    /**
     * @param calls        exact, for a private skill; null when the public path answers with a floor
     * @param accounts     distinct people, exact for private; null on the public path
     * @param joins        library entries — a different question from "used it", and exact
     * @param callsFloor   M15's bucket floor on the public path; null for private
     * @param accountsFloor the same for distinct accounts; null for private
     */
    record OwnerUsage(boolean publicSkill, Long calls, Integer accounts, int joins, String last,
            Integer callsFloor, Integer accountsFloor, List<Collaborator> users) {
        public OwnerUsage {
            users = List.copyOf(users);
        }
    }

    /**
     * @param who      a handle, resolved by the use case
     * @param role     the grant's role, or "所有者" on the first row
     * @param invoke   calls in the window
     * @param submitted versions submitted in the window
     */
    record Collaborator(String who, boolean owner, String role, long invoke, long submitted,
            String last) {
    }

    record Mine(long calls, int versions, String latest, List<MineVersion> byVersion) {
        public Mine {
            byVersion = List.copyOf(byVersion);
        }
    }

    record MineVersion(String version, String state, long calls, String last) {
    }

    /** One person's calls in a window, before handles and roles are resolved. */
    record ActorInvokes(String actorUserId, long invoke, String last) {
    }

    /** Exact totals for one skill in a window; the public view buckets them before leaving. */
    record PublicRange(long calls, long accounts, String last) {
    }
}
