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
 * V18's vocabulary shift, run against the real migration pair (test-plan T3).
 *
 * <p>The two existing grants are translated by meaning — {@code viewer} read, which is
 * {@code browse}; {@code editor} also submitted, which is {@code write} — and the old words stop
 * being valid values. Like T14 this starts at the schema before the migration, because a regular
 * IT begins from an empty database and can never reproduce "rows that were written under the old
 * vocabulary".
 */
class PermissionLadderMigrationIT {

    private static final String DEMO_NAMESPACE_ID = "01M3HTGC79VYJGM8BFXHX2QYNH";
    private static final String DEMO_USER_ID = "01M3HTG7GCCVBGRPAFFSVSF12W";
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
    void theOldWordsAreTranslatedByMeaningAndThenRefused() throws Exception {
        flyway("17").clean();
        flyway("17").migrate();

        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            sql.executeUpdate("""
                    INSERT INTO skill (id, namespace_id, name, description, frontmatter, visibility,
                                       created_by, created_at, updated_at)
                    VALUES ('skill-1', '%s', 'pdf-tools', 'a skill', '{}', 'private',
                            '%s', '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z')
                    """.formatted(DEMO_NAMESPACE_ID, DEMO_USER_ID));
            sql.executeUpdate("""
                    INSERT INTO skill_grant (skill_id, grantee_id, role, granted_by, created_at)
                    VALUES ('skill-1', '%s', 'viewer', '%s', '2026-09-01T00:00:00Z'),
                           ('skill-1', '%s', 'editor', '%s', '2026-09-01T00:00:00Z')
                    """.formatted(DEMO_USER_ID, DEMO_USER_ID, OTHER_USER_ID, DEMO_USER_ID));
        }

        flyway("18").migrate();

        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            try (ResultSet row = sql.executeQuery(
                    "SELECT count(*) FROM skill_grant WHERE role IN ('viewer', 'editor')")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getInt(1)).as("no old word survives the migration").isZero();
            }
            try (ResultSet row = sql.executeQuery(
                    "SELECT role FROM skill_grant ORDER BY role")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).as("viewer read, so it becomes browse").isEqualTo("browse");
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).as("editor wrote, so it becomes write").isEqualTo("write");
            }
            // The CHECK is the half that keeps the old words from coming back.
            assertThatThrownBy(() -> sql.executeUpdate("""
                    INSERT INTO skill_grant (skill_id, grantee_id, role, granted_by, created_at)
                    VALUES ('skill-1', '%s', 'viewer', '%s', '2026-09-02T00:00:00Z')
                    """.formatted(OTHER_USER_ID, OTHER_USER_ID)))
                    .as("the retired vocabulary is refused by the database itself")
                    .isInstanceOf(SQLException.class);
        } finally {
            // The test database is shared with the Spring suites in this run: leave it at the head
            // so a later context does not meet a schema from the middle of the history.
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
}
