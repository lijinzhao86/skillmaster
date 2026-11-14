package com.skillmasterai.ops.store;

import com.skillmasterai.ops.labels.Evidence;
import com.skillmasterai.ops.labels.LabelClaim;
import com.skillmasterai.ops.ports.LabelStore;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The label side of the operations database.
 *
 * <p>Claims are appended, never updated: the current set is the latest run's rows, and the history
 * answers "why did this skill change". The entry row is the skip key, so it is only written for a
 * push that actually landed (or its failure, which must not be skipped next time).
 */
@Repository
public class JdbcLabelStore implements LabelStore {

    private final JdbcClient db;
    private final ObjectMapper json;

    public JdbcLabelStore(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    @Override
    public boolean evaluated(String skillId, String versionId, String ruleVersion) {
        return db.sql("""
                SELECT version_id, rule_version, result FROM label_entry
                 WHERE skill_id = :skillId
                 ORDER BY created_at DESC
                 LIMIT 1
                """)
                .param("skillId", skillId)
                .query((rs, rowNum) -> new String[]{rs.getString("version_id"),
                        rs.getString("rule_version"), rs.getString("result")})
                .optional()
                .map(row -> "labeled".equals(row[2]) && versionId.equals(row[0])
                        && ruleVersion.equals(row[1]))
                .orElse(false);
    }

    @Override
    @Transactional
    public void record(String runId, String origin, Evaluation evaluation,
            List<LabelClaim> claims) {
        for (LabelClaim claim : claims) {
            db.sql("""
                    INSERT INTO label_claim (id, run_id, origin, skill_id, namespace, name,
                        version_id, definition_id, evidence, rule_id, rule_version, created_at)
                    VALUES (:id, :runId, :origin, :skillId, :namespace, :name, :versionId,
                        :definitionId, :evidence, :ruleId, :ruleVersion, :createdAt)
                    ON CONFLICT DO NOTHING
                    """)
                    .param("id", UUID.randomUUID().toString())
                    .param("runId", runId)
                    .param("origin", origin)
                    .param("skillId", evaluation.skillId())
                    .param("namespace", evaluation.namespace())
                    .param("name", evaluation.name())
                    .param("versionId", evaluation.versionId())
                    .param("definitionId", claim.definitionId())
                    .param("evidence", evidence(claim.evidence()))
                    .param("ruleId", claim.ruleId())
                    .param("ruleVersion", evaluation.ruleVersion())
                    .param("createdAt", Timestamp.from(evaluation.at()))
                    .update();
        }
        db.sql("""
                INSERT INTO label_entry (id, run_id, skill_id, namespace, name, version_id,
                    rule_version, result, labels, note, created_at)
                VALUES (:id, :runId, :skillId, :namespace, :name, :versionId, :ruleVersion,
                    :result, :labels, :note, :createdAt)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.randomUUID().toString())
                .param("runId", runId)
                .param("skillId", evaluation.skillId())
                .param("namespace", evaluation.namespace())
                .param("name", evaluation.name())
                .param("versionId", evaluation.versionId())
                .param("ruleVersion", evaluation.ruleVersion())
                .param("result", evaluation.result())
                .param("labels", evaluation.labels())
                .param("note", evaluation.note())
                .param("createdAt", Timestamp.from(evaluation.at()))
                .update();
    }

    @Override
    public List<Entry> entries(String runId) {
        return db.sql("""
                SELECT skill_id, namespace, name, version_id, result, labels, note, created_at
                  FROM label_entry
                 WHERE run_id = :runId
                 ORDER BY created_at
                """)
                .param("runId", runId)
                .query((rs, rowNum) -> new Entry(rs.getString("skill_id"),
                        rs.getString("namespace"), rs.getString("name"),
                        rs.getString("version_id"), rs.getString("result"),
                        rs.getInt("labels"), rs.getString("note"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    private String evidence(Evidence evidence) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kind", evidence.kind());
        value.put("file", evidence.file());
        value.put("line", evidence.line());
        value.put("quote", evidence.quote());
        return json.writeValueAsString(value);
    }
}
