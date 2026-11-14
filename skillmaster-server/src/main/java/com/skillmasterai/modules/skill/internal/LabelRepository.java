package com.skillmasterai.modules.skill.internal;

import com.skillmasterai.modules.skill.LabelDefinition;
import com.skillmasterai.modules.skill.LabelValue;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reads M12's label vocabulary, a skill's displayed claims and its outcome.
 *
 * <p>Only the read model lives here. Extraction writes through its own use case in P1; this class
 * never decides confidence or evidence. The SQL names M12's tables only.
 */
public final class LabelRepository {

    private final JdbcClient jdbc;

    public LabelRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<LabelDefinition> vocabulary() {
        return jdbc.sql("""
                SELECT id, dimension_key, dimension_name, value, label_short, label_full,
                       group_key, filterable, state, sort_order
                  FROM label_definition
                 WHERE filterable = TRUE AND state IN ('enabled', 'stopped')
                 ORDER BY CASE dimension_key WHEN 'runtime' THEN 0 ELSE 1 END, dimension_key,
                          sort_order
                """)
                .query((rs, rowNum) -> new LabelDefinition(
                        rs.getString("id"), rs.getString("dimension_key"),
                        rs.getString("dimension_name"), rs.getString("value"),
                        rs.getString("label_short"), rs.getString("label_full"),
                        rs.getString("group_key"), rs.getBoolean("filterable"),
                        rs.getString("state"), rs.getInt("sort_order")))
                .list();
    }

    public Optional<String> definitionIdOf(String key) {
        return jdbc.sql("""
                SELECT id FROM label_definition
                 WHERE id = :key AND filterable = TRUE AND state IN ('enabled', 'stopped')
                """)
                .param("key", key)
                .query(String.class)
                .optional();
    }

    /**
     * One batch for a page of cards. The join to {@code skill} is the version rule: an agent claim
     * is evidence about the version it evaluated, while only an operator's confirmed claim is a
     * decision that outlives versions.
     */
    public Map<String, List<LabelValue>> labelsOfSkills(Collection<String> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) {
            return Map.of();
        }
        Map<String, List<LabelValue>> result = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT l.skill_id, d.dimension_key, d.value, d.label_short, d.label_full,
                       d.group_key, d.filterable
                  FROM skill_label l
                  JOIN label_definition d ON d.id = l.definition_id
                  JOIN skill s ON s.id = l.skill_id
                 WHERE l.skill_id = ANY(:ids)
                   AND l.review_state IN ('auto', 'confirmed')
                   -- v66 TD: an agent claim speaks for the version it evaluated; only an
                   -- operator's **confirmed** decision outlives versions. `author` / `upstream`
                   -- exist in the CHECK for later writers and are not displayable yet.
                   AND l.source IN ('agent', 'operator')
                   AND ((l.source = 'operator' AND l.review_state = 'confirmed')
                        OR (l.source = 'agent' AND l.evaluated_version_id = s.current_version_id))
                   AND d.state IN ('enabled', 'stopped')
                   AND d.value NOT IN ('prompt-only', 'free')
                 ORDER BY l.skill_id,
                          CASE d.dimension_key WHEN 'runtime' THEN 0 ELSE 1 END,
                          d.dimension_key, d.sort_order
                """)
                .param("ids", skillIds.toArray(String[]::new))
                .query((rs, rowNum) -> new LabelRow(
                        rs.getString("skill_id"),
                        new LabelValue(rs.getString("dimension_key"), rs.getString("value"),
                                rs.getString("label_short"), rs.getString("label_full"),
                                rs.getString("group_key"), rs.getBoolean("filterable"))))
                .list()
                .forEach(row -> result.computeIfAbsent(row.skillId(), key -> new java.util.ArrayList<>())
                        .add(row.value()));
        return result;
    }

    /** The same version rule as the labels, applied to the one outcome column. */
    public Map<String, String> outcomesOf(Collection<String> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT id, outcome FROM skill
                 WHERE id = ANY(:ids)
                   AND outcome IS NOT NULL
                   AND (outcome_source = 'operator'
                        OR outcome_evaluated_version_id = current_version_id)
                """)
                .param("ids", skillIds.toArray(String[]::new))
                .query((rs, rowNum) -> new String[]{rs.getString("id"), rs.getString("outcome")})
                .list()
                .forEach(row -> result.put(row[0], row[1]));
        return result;
    }

    private record LabelRow(String skillId, LabelValue value) {
    }
}
