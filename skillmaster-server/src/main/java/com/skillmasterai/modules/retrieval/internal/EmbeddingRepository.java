package com.skillmasterai.modules.retrieval.internal;

import com.skillmasterai.common.Timestamps;
import java.util.List;
import java.util.StringJoiner;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The only SQL in M13, over the one table it owns.
 *
 * <p><strong>Nothing here names another module's table.</strong> The candidate query returns
 * {@code (skill_id, score)} and stops; whether that skill is visible to this caller is
 * M12's question, asked of M12's seam afterwards. The alternative — a JOIN against {@code skill} —
 * would put a second module's table in this statement, which §2.5 rule 1 forbids and
 * {@code TableOwnershipTest} fails on. Over-fetching and filtering afterwards is the price, and it
 * is the same price the TD already accepted for the HNSW case.
 */
public final class EmbeddingRepository {

    private final JdbcClient jdbc;

    public EmbeddingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One row of the index, before visibility. */
    public record Hit(String skillId, double score) {
    }

    /**
     * The nearest vectors, best first.
     *
     * <p><strong>Exact scan, deliberately</strong> (v19 TD §检索链): at the sizes this ships with,
     * a sequential scan over a few thousand rows is a millisecond and loses nothing; an HNSW index
     * would trade recall for a speed nobody is waiting for. The {@code model} predicate is what
     * keeps two generations of vectors from being compared — they are not comparable.
     *
     * <p>The vector travels as a text literal and is cast by PostgreSQL. JDBC has no vector type,
     * and an array of {@code float4} would need {@code vector(float4[])} to exist; the literal
     * {@code [0.1,0.2]} is the form the extension documents for input.
     */
    public List<Hit> nearest(String model, float[] vector, int limit) {
        return jdbc.sql("""
                SELECT skill_id, 1 - (embedding <=> CAST(:vector AS vector)) AS score
                  FROM skill_embedding
                 WHERE model = :model
                 ORDER BY embedding <=> CAST(:vector AS vector)
                 LIMIT :limit
                """)
                .param("model", model)
                .param("vector", literalOf(vector))
                .param("limit", limit)
                .query((rs, rowNum) -> new Hit(rs.getString("skill_id"), rs.getDouble("score")))
                .list();
    }

    /**
     * The nearest vectors <em>within</em> a given set of skills.
     *
     * <p><strong>This is how the private domain stays exact.</strong> Restricting the scan before
     * the top-k — rather than fetching a prefix of the whole corpus and filtering it afterwards —
     * is the difference between "the best of my skills" and "whichever of my skills happened to
     * land in the global top 200". The id set comes from M12, which is the module that knows who
     * may see what.
     *
     * <p>An empty set answers empty without touching the database: {@code = ANY('{}')} is false
     * everywhere, so running it would only cost a round trip.
     */
    public List<Hit> nearestWithin(String model, float[] vector, List<String> skillIds, int limit) {
        if (skillIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT skill_id, 1 - (embedding <=> CAST(:vector AS vector)) AS score
                  FROM skill_embedding
                 WHERE model = :model
                   AND skill_id = ANY(:skillIds)
                 ORDER BY embedding <=> CAST(:vector AS vector)
                 LIMIT :limit
                """)
                .param("model", model)
                .param("vector", literalOf(vector))
                .param("skillIds", skillIds.toArray(String[]::new))
                .param("limit", limit)
                .query((rs, rowNum) -> new Hit(rs.getString("skill_id"), rs.getDouble("score")))
                .list();
    }

    /**
     * Whether this exact text is already indexed for this model.
     *
     * <p>What makes {@code text_hash} load-bearing rather than decoration: a republish that changes
     * nothing — a rollback followed by the same content, which is the normal way back — finds its
     * own hash here and skips the provider call entirely.
     */
    public boolean hasVectorFor(String skillId, String model, String textHash) {
        return jdbc.sql("""
                SELECT 1 FROM skill_embedding
                 WHERE skill_id = :skillId AND model = :model AND text_hash = :textHash
                """)
                .param("skillId", skillId)
                .param("model", model)
                .param("textHash", textHash)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /**
     * Records the skill's one vector for this model.
     *
     * <p>An upsert on {@code (skill_id, model)}: a re-embed overwrites in place, so there is never a
     * second row to choose between. The key says that a skill has exactly one text per model, which
     * is true because the embedded text is the published projection on the skill row.
     */
    public void remember(String skillId, String model, String textHash, float[] vector,
            String at) {
        jdbc.sql("""
                INSERT INTO skill_embedding (skill_id, model, text_hash, embedding, updated_at)
                VALUES (:skillId, :model, :textHash, CAST(:vector AS vector), :at)
                ON CONFLICT (skill_id, model) DO UPDATE
                   SET text_hash = EXCLUDED.text_hash,
                       embedding = EXCLUDED.embedding,
                       updated_at = EXCLUDED.updated_at
                """)
                .param("skillId", skillId)
                .param("model", model)
                .param("textHash", textHash)
                .param("vector", literalOf(vector))
                .param("at", at)
                .update();
    }

    /** The textual form pgvector reads: {@code [0.1,0.2,...]}, no spaces, no locale. */
    static String literalOf(float[] vector) {
        StringJoiner out = new StringJoiner(",", "[", "]");
        for (float value : vector) {
            out.add(Float.toString(value));
        }
        return out.toString();
    }
}
