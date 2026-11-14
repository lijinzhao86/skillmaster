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
     * One batch for a page of cards.
     *
     * <p><strong>No source or version filter any more.</strong> Since v72 the table only holds final
     * values (ADR 0197); whatever reached it is displayable, and the vocabulary's state is the one
     * remaining rule. The default values are still hidden here, because they exist for filtering.
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
                 WHERE l.skill_id = ANY(:ids)
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

    /** One visible sentence per skill; v72 removed the provenance columns, not the column. */
    public Map<String, String> outcomesOf(Collection<String> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT id, outcome FROM skill
                 WHERE id = ANY(:ids)
                   AND outcome IS NOT NULL
                """)
                .param("ids", skillIds.toArray(String[]::new))
                .query((rs, rowNum) -> new String[]{rs.getString("id"), rs.getString("outcome")})
                .list()
                .forEach(row -> result.put(row[0], row[1]));
        return result;
    }

    /** The dimension a definition belongs to, for the write seam's validation. */
    public Optional<String> dimensionOf(String definitionId) {
        return jdbc.sql("SELECT dimension_key FROM label_definition WHERE id = :id")
                .param("id", definitionId)
                .query(String.class)
                .optional();
    }

    /**
     * Replaces a skill's whole label set. The caller has already validated every id.
     *
     * <p><strong>Delete then insert, not a diff.</strong> The endpoint's contract is "this is the
     * set"; a value that stopped matching must disappear, and making the server compute which rows
     * changed would be a second implementation of the rules.
     */
    public void replace(String skillId, List<String> definitionIds) {
        jdbc.sql("DELETE FROM skill_label WHERE skill_id = :skillId")
                .param("skillId", skillId)
                .update();
        for (String definitionId : definitionIds) {
            jdbc.sql("""
                    INSERT INTO skill_label (skill_id, definition_id)
                    VALUES (:skillId, :definitionId)
                    ON CONFLICT DO NOTHING
                    """)
                    .param("skillId", skillId)
                    .param("definitionId", definitionId)
                    .update();
        }
    }

    private record LabelRow(String skillId, LabelValue value) {
    }
}
