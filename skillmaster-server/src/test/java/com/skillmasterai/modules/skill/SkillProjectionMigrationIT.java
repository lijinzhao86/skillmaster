package com.skillmasterai.modules.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * V21's one-time backfill, run against the real migration pair (test-plan T14).
 *
 * <p><strong>It has to start at V20.</strong> The backfill's whole job is to read the content rows
 * that already exist and write them onto {@code skill}; a test that ran on a schema already at V21
 * would assert on an empty migration. So this drives Flyway directly — clean, migrate to 20, insert
 * fixtures in the pre-V21 shape, migrate to latest — rather than going through the Spring context,
 * which always migrates to the head on startup.
 *
 * <p>Two fixtures, because they are the two answers the projection has to give: a skill with a
 * published version plus drafts and a discarded one, and a skill with only a published version. The
 * second is where a wrong query writes a draft count of one, or leaves the newest-draft columns
 * pointing at a row that does not exist.
 */
class SkillProjectionMigrationIT {

    private static final String DEMO_NAMESPACE_ID = "01M3HTGC79VYJGM8BFXHX2QYNH";
    private static final String DEMO_USER_ID = "01M3HTG7GCCVBGRPAFFSVSF12W";

    private static final String URL = Optional.ofNullable(System.getenv("SKILLMASTER_TEST_DB_URL"))
            .orElse("jdbc:postgresql://localhost:5432/skillmaster_test");
    private static final String USER = Optional.ofNullable(System.getenv("SKILLMASTER_TEST_DB_USER"))
            .or(() -> Optional.ofNullable(System.getenv("SKILLMASTER_DB_USER")))
            .orElse(System.getProperty("user.name"));
    private static final String PASSWORD = Optional
            .ofNullable(System.getenv("SKILLMASTER_TEST_DB_PASSWORD"))
            .or(() -> Optional.ofNullable(System.getenv("SKILLMASTER_DB_PASSWORD")))
            .orElse("");

    @Test
    void theBackfillFillsTheCardsFromTheVersionsThatAlreadyExist() throws Exception {
        flyway("20").clean();
        flyway("20").migrate();

        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            // Published 1.0.0, a draft 2.0.0, and a discarded 0.9.0. The draft is the newest by
            // number, and the published one is what the pointer names.
            insertSkill(sql, "skill-with-drafts", "with-drafts");
            insertVersion(sql, "skill-with-drafts", 1, "0.9.0", "discarded",
                    "2026-09-01T00:00:00Z", "discarded title", "{}");
            insertVersion(sql, "skill-with-drafts", 2, "1.0.0", "published",
                    "2026-09-02T00:00:00Z", "published title",
                    "{\"metadata\": {\"when_to_use\": \"要动 PDF 的时候用\"}}");
            insertVersion(sql, "skill-with-drafts", 3, "2.0.0", "draft",
                    "2026-09-03T00:00:00Z", "draft title",
                    "{\"metadata\": {\"when_to_use\": \"草稿里的\"}}");
            sql.executeUpdate("UPDATE skill SET current_version_id = 'v-skill-with-drafts-2'"
                    + " WHERE id = 'skill-with-drafts'");

            // Published only: nothing waiting, and the draft columns must stay null rather than
            // pointing at the published row.
            insertSkill(sql, "skill-published-only", "published-only");
            insertVersion(sql, "skill-published-only", 1, "1.2.3", "published",
                    "2026-10-07T00:00:00Z", "only title",
                    "{\"when_to_use\": \"顶层键也认得\"}");
            sql.executeUpdate("UPDATE skill SET current_version_id = 'v-skill-published-only-1'"
                    + " WHERE id = 'skill-published-only'");
        }

        flyway("latest").migrate();

        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            try (ResultSet row = sql.executeQuery("SELECT * FROM skill WHERE id = 'skill-with-drafts'")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("current_version_name")).isEqualTo("1.0.0");
                assertThat(row.getString("current_digest")).isEqualTo("digest-v-skill-with-drafts-2");
                assertThat(row.getString("current_version_submitted_at"))
                        .isEqualTo("2026-09-02T00:00:00Z");
                assertThat(row.getString("when_to_use"))
                        .as("the published version's frontmatter, under metadata")
                        .isEqualTo("要动 PDF 的时候用");
                assertThat(row.getString("title")).isEqualTo("published title");
                assertThat(row.getInt("draft_count")).isEqualTo(1);
                assertThat(row.getString("newest_draft_submitted_at"))
                        .isEqualTo("2026-09-03T00:00:00Z");
                assertThat(row.getString("newest_draft_version")).isEqualTo("2.0.0");
                assertThat(row.getString("newest_draft_digest"))
                        .isEqualTo("digest-v-skill-with-drafts-3");
                assertThat(row.getString("newest_title")).isEqualTo("draft title");
                assertThat(row.getString("newest_description")).isEqualTo("draft title");
            }

            try (ResultSet row = sql.executeQuery(
                    "SELECT * FROM skill WHERE id = 'skill-published-only'")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("current_version_name")).isEqualTo("1.2.3");
                assertThat(row.getString("current_version_submitted_at"))
                        .isEqualTo("2026-10-07T00:00:00Z");
                assertThat(row.getString("when_to_use"))
                        .as("the fallback makes the pre-V21 top-level spelling readable")
                        .isEqualTo("顶层键也认得");
                assertThat(row.getInt("draft_count")).isZero();
                assertThat(row.getString("newest_draft_submitted_at")).isNull();
                assertThat(row.getString("newest_draft_version")).isNull();
                assertThat(row.getString("newest_title")).isNull();
            }
        } finally {
            // Shared test database: leave it at the head for whatever runs next.
            flyway("latest").migrate();
        }
    }

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(URL, USER, PASSWORD)
                .cleanDisabled(false)
                .target(target)
                .load();
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    /** A skill in the pre-V21 shape: no projection columns exist yet at this point. */
    private static void insertSkill(Statement sql, String id, String name) throws SQLException {
        sql.executeUpdate("""
                INSERT INTO skill (id, namespace_id, name, title, description, frontmatter,
                                   visibility, created_by, created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'initial title', 'initial description', '{}', 'private',
                        '%s', '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z')
                """.formatted(id, DEMO_NAMESPACE_ID, name, DEMO_USER_ID));
    }

    private static void insertVersion(Statement sql, String skillId, int number, String version,
            String state, String submittedAt, String title, String frontmatter) throws SQLException {
        String id = "v-" + skillId + "-" + number;
        sql.executeUpdate("""
                INSERT INTO skill_version (id, skill_id, number, version, digest, file_count,
                                           total_bytes, changelog, source, submitted_by, submitted_at,
                                           state, state_at, title, description, frontmatter)
                VALUES ('%s', '%s', %d, '%s', 'digest-%s', 0, 0, '', 'zip', '%s', '%s', '%s',
                        CASE WHEN '%s' = 'draft' THEN NULL ELSE '%s' END,
                        '%s', '%s', '%s')
                """.formatted(id, skillId, number, version, id, DEMO_USER_ID, submittedAt,
                        state, state, submittedAt, title, title, frontmatter));
    }
}
