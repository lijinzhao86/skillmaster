package com.skillmasterai.ops.store;

import com.skillmasterai.ops.imports.SourceAdapter.SourceBinding;
import com.skillmasterai.ops.ports.RunStore;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The runs table, written through one repository.
 *
 * <p>JSON columns are stored as text: nothing here queries by their inside, and a text column
 * round-trips a map without asking the driver for a JSONB type on the way in.
 */
@Repository
public class JdbcRunStore implements RunStore {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcClient db;
    private final ObjectMapper json;

    public JdbcRunStore(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    @Override
    public Run start(String kind, String trigger, Map<String, Object> params) {
        Run run = new Run(UUID.randomUUID().toString(), kind, trigger, "running", params, Map.of(),
                Instant.now(), null, null);
        db.sql("INSERT INTO job_run (id, kind, trigger, status, params, summary, started_at)"
                        + " VALUES (:id, :kind, :trigger, :status, :params, :summary, :startedAt)")
                .param("id", run.id())
                .param("kind", run.kind())
                .param("trigger", run.trigger())
                .param("status", run.status())
                .param("params", write(run.params()))
                .param("summary", write(run.summary()))
                .param("startedAt", java.sql.Timestamp.from(run.startedAt()))
                .update();
        return run;
    }

    @Override
    public void finish(String runId, String status, Map<String, Object> summary, String error) {
        db.sql("UPDATE job_run SET status = :status, summary = :summary, finished_at = :finishedAt,"
                        + " error = :error WHERE id = :id")
                .param("status", status)
                .param("summary", write(summary))
                .param("finishedAt", java.sql.Timestamp.from(Instant.now()))
                .param("error", error)
                .param("id", runId)
                .update();
    }

    @Override
    public void recordEntry(EntryResult entry) {
        db.sql("INSERT INTO import_entry (id, run_id, binding, revision, platform_name, skill_id,"
                        + " draft_id, result, note, created_at)"
                        + " VALUES (:id, :runId, :binding, :revision, :platformName, :skillId,"
                        + " :draftId, :result, :note, :createdAt)")
                .param("id", UUID.randomUUID().toString())
                .param("runId", entry.runId())
                .param("binding", writeBinding(entry.binding()))
                .param("revision", entry.revision())
                .param("platformName", entry.platformName())
                .param("skillId", entry.skillId())
                .param("draftId", entry.draftId())
                .param("result", entry.result())
                .param("note", entry.note())
                .param("createdAt", java.sql.Timestamp.from(entry.createdAt()))
                .update();
    }

    @Override
    public Optional<Run> find(String id) {
        return db.sql("SELECT * FROM job_run WHERE id = :id")
                .param("id", id)
                .query((rs, row) -> readRun(rs))
                .optional();
    }

    @Override
    public List<EntryResult> entries(String runId) {
        return db.sql("SELECT run_id, binding, revision, platform_name, skill_id, draft_id, result,"
                        + " note, created_at FROM import_entry WHERE run_id = :runId"
                        + " ORDER BY created_at")
                .param("runId", runId)
                .query((rs, row) -> new EntryResult(rs.getString("run_id"),
                        bindingOf(rs.getString("binding")), rs.getString("revision"),
                        rs.getString("platform_name"), rs.getString("skill_id"),
                        rs.getString("draft_id"), rs.getString("result"), rs.getString("note"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    private SourceBinding bindingOf(String jsonValue) {
        Map<String, Object> value = read(jsonValue);
        return new SourceBinding(String.valueOf(value.getOrDefault("kind", "")),
                String.valueOf(value.getOrDefault("locator", "")),
                String.valueOf(value.getOrDefault("path", "")), "", "");
    }

    @Override
    public List<Run> recent(String kind, int limit) {
        return db.sql("SELECT * FROM job_run WHERE kind = :kind ORDER BY started_at DESC LIMIT :limit")
                .param("kind", kind)
                .param("limit", limit)
                .query((rs, row) -> readRun(rs))
                .list();
    }

    private Run readRun(java.sql.ResultSet rs) throws java.sql.SQLException {
        java.sql.Timestamp finished = rs.getTimestamp("finished_at");
        return new Run(rs.getString("id"), rs.getString("kind"), rs.getString("trigger"),
                rs.getString("status"), read(rs.getString("params")), read(rs.getString("summary")),
                rs.getTimestamp("started_at").toInstant(),
                finished == null ? null : finished.toInstant(), rs.getString("error"));
    }

    private String write(Map<String, Object> value) {
        return json.writeValueAsString(value);
    }

    private String writeBinding(SourceBinding binding) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kind", binding.kind());
        value.put("locator", binding.locator());
        value.put("path", binding.path());
        return json.writeValueAsString(value);
    }

    private Map<String, Object> read(String value) {
        return value == null || value.isBlank() ? Map.of() : json.readValue(value, MAP);
    }
}
