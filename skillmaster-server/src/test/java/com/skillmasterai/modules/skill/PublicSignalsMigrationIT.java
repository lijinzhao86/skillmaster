package com.skillmasterai.modules.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * V36's four tables and the one-time backfill of the skills that already existed.
 *
 * <p><strong>It starts before V36.</strong> The backfill reads existing official skills; a schema
 * already at the head would make the assertion run over an empty statement. This drives Flyway
 * directly (clean, migrate to 35, insert, migrate to latest), the same way the other migration ITs
 * do.</p>
 *
 * <p>Two skills, because the backfill has to tell them apart: an official one gets a source, a
 * pending snapshot and a missing license; a non-official one gets nothing. The constraints are the
 * other half: one origin per skill, and one usage row per account per day.</p>
 */
class PublicSignalsMigrationIT {

    private static final String OFFICIAL_NAMESPACE_ID = "01M3HTGC79XSQK3TS3ZF0BG05F";
    private static final String OFFICIAL_USER_ID = "01M3HTG7GDZGTDME9B136ZVAW4";
    private static final String OTHER_NAMESPACE_ID = "01M3HTGC79CHKDB4Q0T2JMRCWV";
    private static final String OTHER_USER_ID = "01M3HTG7GDQ71Q28CCP7J0HM8T";

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
    void theBackfillGivesEveryOfficialSkillASourceAndAProvisionalLicense() throws Exception {
        flyway("35").clean();
        flyway("35").migrate();

        try {
            try (Connection connection = connect(); Statement sql = connection.createStatement()) {
                insertSkill(sql, "s-official", OFFICIAL_NAMESPACE_ID, OFFICIAL_USER_ID,
                        "official-skill");
                insertVersion(sql, "s-official", "v-official");
                sql.executeUpdate(
                        "UPDATE skill SET current_version_id = 'v-official' WHERE id = 's-official'");

                // A legacy official skill whose SKILL.md declares a license: V38 has to keep that
                // declaration instead of leaving V36's provisional `missing` in place.
                insertSkill(sql, "s-declared", OFFICIAL_NAMESPACE_ID, OFFICIAL_USER_ID,
                        "official-declared");
                insertVersion(sql, "s-declared", "v-declared",
                        "{\"metadata\":{\"license\":\"MIT\"}}");
                sql.executeUpdate(
                        "UPDATE skill SET current_version_id = 'v-declared' WHERE id = 's-declared'");

                insertSkill(sql, "s-other", OTHER_NAMESPACE_ID, OTHER_USER_ID, "other-skill");
                insertVersion(sql, "s-other", "v-other");
                sql.executeUpdate(
                        "UPDATE skill SET current_version_id = 'v-other' WHERE id = 's-other'");
            }

            flyway("latest").migrate();
            assertBackfill();
        } finally {
            // Shared database: leave it at the head for whichever test runs next, even when an
            // assertion above failed.
            flyway("latest").migrate();
        }
    }

    private static void assertBackfill() throws SQLException {
        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            try (ResultSet row = sql.executeQuery("""
                    SELECT role, kind, locator, path, label FROM skill_source
                     WHERE skill_id = 's-official'
                    """)) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("role")).isEqualTo("origin");
                assertThat(row.getString("kind")).isEqualTo("official");
                assertThat(row.getString("locator")).isEqualTo("skillmaster/official-skill");
                assertThat(row.getString("path")).isEmpty();
                assertThat(row.getString("label")).isEqualTo("官方");
            }
            try (ResultSet row = sql.executeQuery("""
                    SELECT snap.status, snap.signals, snap.skill_version_id
                      FROM skill_source_snapshot snap
                      JOIN skill_source src ON src.id = snap.source_id
                     WHERE src.skill_id = 's-official'
                    """)) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("status")).isEqualTo("pending");
                assertThat(row.getString("signals")).isEqualTo("[]");
                assertThat(row.getString("skill_version_id")).isEqualTo("v-official");
            }
            try (ResultSet row = sql.executeQuery(
                    "SELECT spdx, name, status FROM skill_version_license WHERE version_id = 'v-official'")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("spdx")).isEqualTo("NOASSERTION");
                assertThat(row.getString("name")).isEqualTo("未声明");
                assertThat(row.getString("status")).isEqualTo("missing");
            }

            try (ResultSet row = sql.executeQuery(
                    "SELECT spdx, name, status, detail FROM skill_version_license"
                            + " WHERE version_id = 'v-declared'")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("spdx")).isEqualTo("MIT");
                assertThat(row.getString("name")).isEqualTo("MIT");
                assertThat(row.getString("status")).isEqualTo("declared-only");
                assertThat(row.getString("detail")).contains("declared").contains("MIT");
            }

            try (ResultSet row = sql.executeQuery(
                    "SELECT count(*) AS n FROM skill_source WHERE skill_id = 's-other'")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getInt("n")).as("a non-official skill is not given a source").isZero();
            }

            assertThatThrownBy(() -> sql.executeUpdate("""
                    INSERT INTO skill_source (id, skill_id, role, kind, locator, path, label,
                                              created_at)
                    VALUES ('second-origin', 's-official', 'origin', 'github', 'a/b', '', 'GitHub',
                            '2026-11-05T00:00:00Z')
                    """))
                    .as("one origin per skill")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("skill_source_one_origin");

            sql.executeUpdate("""
                    INSERT INTO skill_usage_day (skill_id, day, account_id, scope)
                    VALUES ('s-official', '2026-11-05', '%s', 'public')
                    """.formatted(OFFICIAL_USER_ID));
            assertThatThrownBy(() -> sql.executeUpdate("""
                    INSERT INTO skill_usage_day (skill_id, day, account_id, scope)
                    VALUES ('s-official', '2026-11-05', '%s', 'public')
                    """.formatted(OFFICIAL_USER_ID)))
                    .as("the same account on the same day is one row")
                    .isInstanceOf(SQLException.class);
        }
    }

    private static void insertSkill(Statement sql, String id, String namespaceId, String userId,
            String name) throws SQLException {
        sql.executeUpdate("""
                INSERT INTO skill (id, namespace_id, name, title, description, frontmatter,
                                   visibility, created_by, created_at, updated_at)
                VALUES ('%s', '%s', '%s', '%s', 'a probe', '{}', 'public', '%s',
                        '2026-11-05T00:00:00Z', '2026-11-05T00:00:00Z')
                """.formatted(id, namespaceId, name, name, userId));
    }

    private static void insertVersion(Statement sql, String skillId, String versionId)
            throws SQLException {
        insertVersion(sql, skillId, versionId, "{}");
    }

    private static void insertVersion(Statement sql, String skillId, String versionId,
            String frontmatter) throws SQLException {
        sql.executeUpdate("""
                INSERT INTO skill_version (id, skill_id, number, version, digest, file_count,
                                           total_bytes, changelog, source, submitted_by, submitted_at,
                                           state, state_at, first_published_at, title, description,
                                           frontmatter)
                VALUES ('%s', '%s', 1, '1.0.0', 'digest-%s', 0, 0, '', 'zip', '%s',
                        '2026-11-05T00:00:00Z', 'published', '2026-11-05T00:00:00Z',
                        '2026-11-05T00:00:00Z', 'probe', 'probe', '%s')
                """.formatted(versionId, skillId, versionId, OFFICIAL_USER_ID, frontmatter));
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(URL, USER, PASSWORD)
                .cleanDisabled(false)
                .target(target)
                .load();
    }
}
