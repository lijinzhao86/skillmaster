package com.skillmasterai.modules.feedback.internal;

import com.skillmasterai.modules.feedback.Feedback;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/**
 * M18's only SQL. Every statement names {@code user_feedback}, the one table the module owns.
 *
 * <p>Diagnostics travel as a JSON string and are cast at the database: the column is {@code jsonb},
 * and a driver-side object would make the write depend on the driver's JSON support. The whitelist
 * is applied before this class sees the map.
 */
public final class FeedbackRepository {

    private static final String COLUMNS = "id, receipt, user_id, skill_address, content, "
            + "content_sha256, consent_version, consent_at, source, diagnostics, archive_key, "
            + "archived_at, created_at";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public FeedbackRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * @return 1 when the row was written, 0 when a unique key already held it.
     *     <p><strong>{@code ON CONFLICT DO NOTHING} rather than an exception.</strong> A duplicate
     *     receipt inside the submission's transaction would abort that transaction; a caught
     *     {@code DuplicateKeyException} cannot be followed by another INSERT on the same
     *     connection. This form leaves the transaction usable so the caller can try a new receipt.
     */
    public int insert(Feedback feedback) {
        String diagnostics = feedback.diagnostics() == null
                ? null : json.writeValueAsString(feedback.diagnostics());
        return jdbc.sql("""
                INSERT INTO user_feedback
                    (id, receipt, user_id, skill_address, content, content_sha256,
                     consent_version, consent_at, source, diagnostics, archive_key, archived_at,
                     created_at)
                VALUES
                    (:id, :receipt, :userId, :skillAddress, :content, :sha, :consentVersion,
                     :consentAt, :source, CAST(:diagnostics AS jsonb), :archiveKey, :archivedAt,
                     :createdAt)
                ON CONFLICT DO NOTHING
                """)
                .param("id", feedback.id())
                .param("receipt", feedback.receipt())
                .param("userId", feedback.userId())
                .param("skillAddress", feedback.skillAddress())
                .param("content", feedback.content())
                .param("sha", feedback.contentSha256())
                .param("consentVersion", feedback.consentVersion())
                .param("consentAt", feedback.consentAt())
                .param("source", feedback.source())
                .param("diagnostics", diagnostics)
                .param("archiveKey", feedback.archiveKey())
                .param("archivedAt", feedback.archivedAt())
                .param("createdAt", feedback.createdAt())
                .update();
    }

    public Optional<Feedback> findByReceiptAndUser(String receipt, String userId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM user_feedback"
                        + " WHERE receipt = :receipt AND user_id = :userId")
                .param("receipt", receipt)
                .param("userId", userId)
                .query((rs, rowNum) -> feedback(rs))
                .optional();
    }

    /**
     * The withdrawal's read: locks the row until the transaction ends, so the archive job cannot
     * record an object key between this read and the DELETE. Without the lock the job could upload
     * the object, the withdrawal could delete the row, and the object would stay in the bucket.
     */
    public Optional<Feedback> lockByReceiptAndUser(String receipt, String userId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM user_feedback"
                        + " WHERE receipt = :receipt AND user_id = :userId FOR UPDATE")
                .param("receipt", receipt)
                .param("userId", userId)
                .query((rs, rowNum) -> feedback(rs))
                .optional();
    }

    public int deleteByReceiptAndUser(String receipt, String userId) {
        return jdbc.sql("DELETE FROM user_feedback WHERE receipt = :receipt AND user_id = :userId")
                .param("receipt", receipt)
                .param("userId", userId)
                .update();
    }

    public int countSince(String userId, String since) {
        long count = jdbc.sql("SELECT count(*) FROM user_feedback"
                        + " WHERE user_id = :userId AND created_at >= :since")
                .param("userId", userId)
                .param("since", since)
                .query(Long.class)
                .single();
        return Math.toIntExact(count);
    }

    public Optional<String> oldestSince(String userId, String since) {
        // `min()` over no rows is one row holding NULL, not zero rows: `optional()` would either
        // wrap the null or throw depending on the mapper, so the null is read as absence here.
        List<String> rows = jdbc.sql("SELECT min(created_at) FROM user_feedback"
                        + " WHERE user_id = :userId AND created_at >= :since")
                .param("userId", userId)
                .param("since", since)
                .query(String.class)
                .list();
        return rows.isEmpty() || rows.get(0) == null
                ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<Feedback> pendingArchive(int limit) {
        // The predicate names the column the partial index is on; `archive_key IS NULL` would be
        // the same set but would not match the index, turning every run into a full scan.
        return jdbc.sql("SELECT " + COLUMNS + " FROM user_feedback"
                        + " WHERE archived_at IS NULL ORDER BY created_at, id LIMIT :limit")
                .param("limit", limit)
                .query((rs, rowNum) -> feedback(rs))
                .list();
    }

    /**
     * @return 1 when this run recorded the key, 0 when the row is gone (withdrawn) or somebody else
     *     already recorded one. The caller has to tell those two apart.
     */
    public int markArchived(String id, String key, String at) {
        return jdbc.sql("UPDATE user_feedback SET archive_key = :key, archived_at = :at"
                        + " WHERE id = :id AND archived_at IS NULL")
                .param("key", key)
                .param("at", at)
                .param("id", id)
                .update();
    }

    public boolean existsById(String id) {
        return jdbc.sql("SELECT 1 FROM user_feedback WHERE id = :id")
                .param("id", id)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    private Feedback feedback(ResultSet rs) throws SQLException {
        String diagnostics = rs.getString("diagnostics");
        return new Feedback(
                rs.getString("id"),
                rs.getString("receipt"),
                rs.getString("user_id"),
                rs.getString("skill_address"),
                rs.getString("content"),
                rs.getString("content_sha256"),
                rs.getString("consent_version"),
                rs.getString("consent_at"),
                rs.getString("source"),
                diagnostics == null ? null : readDiagnostics(diagnostics),
                rs.getString("archive_key"),
                rs.getString("archived_at"),
                rs.getString("created_at"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readDiagnostics(String raw) {
        return json.readValue(raw, Map.class);
    }
}
