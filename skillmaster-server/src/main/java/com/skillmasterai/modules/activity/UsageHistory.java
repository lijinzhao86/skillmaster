package com.skillmasterai.modules.activity;

import java.util.List;

/**
 * M17's read edge for one person (v58).
 *
 * <p>Everything here is scoped by the account the caller supplies, and the window is the
 * product's 90 days — not a parameter, because a record older than that has been exported and
 * dropped. The two queries answer the page and its stats; both are built from the same rows, so
 * their numbers cannot disagree.
 */
public interface UsageHistory {

    int WINDOW_DAYS = 90;

    /** One page of the interaction list, newest first. */
    Page interactions(String actorUserId, int page, int limit, String at);

    /** The last 90 days for one account. */
    Stats stats(String actorUserId, String at);

    /** The owners' per-person totals for one skill, before handles and roles are resolved. */
    List<UserDay> userDays(String skillId, String at);

    /** One person's use of one skill, split by version. */
    Mine mine(String skillId, String actorUserId, String at);

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

    /** One person's totals for the owner view, before handles and roles are resolved. */
    record UserDay(String actorUserId, long invoke, long submitted, String last) {
    }
}
