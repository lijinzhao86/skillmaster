package com.skillmasterai.modules.metrics.internal;

import com.skillmasterai.modules.metrics.SkillLoad;
import com.skillmasterai.modules.metrics.UsageScope;
import com.skillmasterai.modules.metrics.UsageSnapshot;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * M15's only SQL. Every statement here names only M15's two tables (rule ①, `TableOwnershipTest`).
 */
public final class UsageRepository {

    private final JdbcClient jdbc;

    public UsageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(SkillLoad load) {
        // The deduplicated series: one row per account per day per scope, however many loads.
        jdbc.sql("INSERT INTO skill_usage_day (skill_id, day, account_id, scope)"
                        + " VALUES (:skill, :day, :account, :scope) ON CONFLICT DO NOTHING")
                .param("skill", load.skillId())
                .param("day", load.day().toString())
                .param("account", load.accountId())
                .param("scope", load.scope().wire())
                .update();
        // The cumulative series answers a different question (ADR 0152) and advances on every
        // load, including one that the day row deduplicated.
        jdbc.sql("INSERT INTO skill_usage_total (skill_id, scope, calls)"
                        + " VALUES (:skill, :scope, 1)"
                        + " ON CONFLICT (skill_id, scope)"
                        + " DO UPDATE SET calls = skill_usage_total.calls + 1")
                .param("skill", load.skillId())
                .param("scope", load.scope().wire())
                .update();
    }

    public Map<String, UsageSnapshot> snapshots(Collection<String> skillIds, UsageScope scope,
            LocalDate today) {
        if (skillIds.isEmpty()) {
            return Map.of();
        }
        String since = today.minusDays(29).toString();
        Map<String, Integer> invokers = new HashMap<>();
        jdbc.sql("SELECT skill_id, count(DISTINCT account_id) AS n FROM skill_usage_day"
                        + " WHERE scope = :scope AND day >= :since AND skill_id IN (:ids)"
                        + " GROUP BY skill_id")
                .param("scope", scope.wire())
                .param("since", since)
                .param("ids", skillIds)
                .query((rs, rowNum) -> Map.entry(rs.getString("skill_id"), rs.getInt("n")))
                .list()
                .forEach(entry -> invokers.put(entry.getKey(), entry.getValue()));

        Map<String, Long> calls = new HashMap<>();
        jdbc.sql("SELECT skill_id, calls FROM skill_usage_total"
                        + " WHERE scope = :scope AND skill_id IN (:ids)")
                .param("scope", scope.wire())
                .param("ids", skillIds)
                .query((rs, rowNum) -> Map.entry(rs.getString("skill_id"), rs.getLong("calls")))
                .list()
                .forEach(entry -> calls.put(entry.getKey(), entry.getValue()));

        Map<String, String> lastUsed = new HashMap<>();
        jdbc.sql("SELECT skill_id, max(day) AS last FROM skill_usage_day"
                        + " WHERE scope = :scope AND skill_id IN (:ids) GROUP BY skill_id")
                .param("scope", scope.wire())
                .param("ids", skillIds)
                .query((rs, rowNum) -> Map.entry(rs.getString("skill_id"),
                        rs.getString("last")))
                .list()
                .forEach(entry -> lastUsed.put(entry.getKey(), entry.getValue()));

        Set<String> seen = new HashSet<>(invokers.keySet());
        seen.addAll(calls.keySet());
        seen.addAll(lastUsed.keySet());
        Map<String, UsageSnapshot> found = new HashMap<>();
        for (String skillId : seen) {
            long total = calls.getOrDefault(skillId, 0L);
            int distinct = invokers.getOrDefault(skillId, 0);
            String last = lastUsed.get(skillId);
            found.put(skillId, new UsageSnapshot(
                    total > 0,
                    UsageSnapshot.floorOf(total),
                    UsageSnapshot.floorOf(distinct),
                    last == null ? null : LocalDate.parse(last)));
        }
        return found;
    }

    public int deleteBefore(LocalDate day, UsageScope scope) {
        return jdbc.sql("DELETE FROM skill_usage_day WHERE scope = :scope AND day < :day")
                .param("scope", scope.wire())
                .param("day", day.toString())
                .update();
    }
}
