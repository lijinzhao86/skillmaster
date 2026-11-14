package com.skillmasterai.ops.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.skillmasterai.ops.labels.Evidence;
import com.skillmasterai.ops.labels.LabelClaim;
import com.skillmasterai.ops.ports.LabelStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

/**
 * The SQL behind the claims and the skip key. Needs a real PostgreSQL; CI supplies one and a local
 * run without {@code OPS_TEST_DB_URL} skips it.
 */
class JdbcLabelStoreTest {

    private static JdbcClient db;
    private static JdbcLabelStore store;

    @BeforeAll
    static void migrate() {
        String url = System.getenv("OPS_TEST_DB_URL");
        assumeTrue(url != null && !url.isBlank(), "OPS_TEST_DB_URL is not set");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url,
                System.getenv("OPS_TEST_DB_USER"), System.getenv("OPS_TEST_DB_PASSWORD"));
        dataSource.setDriverClassName("org.postgresql.Driver");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load()
                .migrate();
        db = JdbcClient.create(dataSource);
        store = new JdbcLabelStore(db, new ObjectMapper());
    }

    @BeforeEach
    void empty() {
        db.sql("TRUNCATE TABLE label_claim, label_entry, job_run CASCADE").update();
    }

    @Test
    void aSuccessfulEvaluationIsTheSkipKeyAndTheFailureIsNot() {
        String run = run();
        store.record(run, "refresh", evaluation("skill-1", "version-1", "labeled", 1),
                List.of(claim("runtime:python")));

        assertThat(store.evaluated("skill-1", "version-1", "fixed-r1")).isTrue();
        assertThat(store.evaluated("skill-1", "version-2", "fixed-r1")).isFalse();
        assertThat(store.evaluated("skill-1", "version-1", "fixed-r2")).isFalse();

        String failed = run();
        store.record(failed, "refresh", evaluation("skill-2", "version-1", "push_failed", 1),
                List.of(claim("runtime:docker")));
        assertThat(store.evaluated("skill-2", "version-1", "fixed-r1")).isFalse();
    }

    @Test
    void claimsKeepTheirEvidenceAndTheEntryIsReadable() {
        String run = run();
        store.record(run, "import", evaluation("skill-1", "version-1", "labeled", 2),
                List.of(claim("runtime:python"), claim("runtime:docker")));

        List<LabelStore.Entry> entries = store.entries(run);
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().result()).isEqualTo("labeled");
        assertThat(entries.getFirst().labels()).isEqualTo(2);

        List<String> evidence = db.sql("SELECT evidence FROM label_claim ORDER BY definition_id")
                .query(String.class).list();
        assertThat(evidence).hasSize(2);
        assertThat(evidence.getFirst()).contains("\"kind\":\"command\"")
                .contains("\"quote\":\"docker run\"");
    }

    private static String run() {
        String id = UUID.randomUUID().toString();
        db.sql("""
                INSERT INTO job_run (id, kind, trigger, status, params, summary, started_at)
                VALUES (:id, 'label', 'manual', 'running', '{}', '{}', :at)
                """).param("id", id).param("at", Timestamp.from(Instant.now())).update();
        return id;
    }

    private static LabelStore.Evaluation evaluation(String skillId, String versionId, String result,
            int labels) {
        return new LabelStore.Evaluation(skillId, "skillmaster", "pdf-tools", versionId,
                "fixed-r1", result, labels, null, Instant.now());
    }

    private static LabelClaim claim(String definitionId) {
        return new LabelClaim(definitionId, "test.rule",
                new Evidence(Evidence.COMMAND, "SKILL.md", 3, "docker run"));
    }
}
