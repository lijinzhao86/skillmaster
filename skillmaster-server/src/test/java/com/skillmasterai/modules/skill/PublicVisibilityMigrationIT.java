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
 * V35's one-time correction: rows that were public outside the official namespace become private
 * (ADR 0146).
 *
 * <p><strong>It has to start before V35.</strong> The migration's job is to read rows that already
 * exist; a test running on a schema already at V35 would assert on an empty statement. So this
 * drives Flyway directly — clean, migrate to V34, insert the five rows, migrate to latest — the
 * way {@link SkillProjectionMigrationIT} does.
 *
 * <p>Five rows because the migration has to tell them apart: an official public row at each rung
 * (kept, rung unchanged), a non-official public row at each rung (converted), and one non-official
 * private row (untouched). The count at the end is the assertion that nothing was deleted.
 */
class PublicVisibilityMigrationIT {

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
    void nonOfficialPublicRowsBecomePrivateAndNothingIsDeleted() throws Exception {
        flyway("34").clean();
        flyway("34").migrate();

        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            insert(sql, "s-official-invoke", OFFICIAL_NAMESPACE_ID, OFFICIAL_USER_ID,
                    "official-invoke", "public", "invoke");
            insert(sql, "s-official-browse", OFFICIAL_NAMESPACE_ID, OFFICIAL_USER_ID,
                    "official-browse", "public", "browse");
            insert(sql, "s-outside-invoke", OTHER_NAMESPACE_ID, OTHER_USER_ID,
                    "outside-invoke", "public", "invoke");
            insert(sql, "s-outside-browse", OTHER_NAMESPACE_ID, OTHER_USER_ID,
                    "outside-browse", "public", "browse");
            insert(sql, "s-outside-private", OTHER_NAMESPACE_ID, OTHER_USER_ID,
                    "outside-private", "private", null);
        }

        flyway("latest").migrate();

        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            for (String[] official : new String[][] {
                    {"s-official-invoke", "invoke"}, {"s-official-browse", "browse"}}) {
                try (ResultSet row = sql.executeQuery(
                        "SELECT visibility, public_rung FROM skill WHERE id = '" + official[0] + "'")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString("visibility")).isEqualTo("public");
                    assertThat(row.getString("public_rung"))
                            .as("the official rung survives unchanged")
                            .isEqualTo(official[1]);
                }
            }
            for (String id : new String[] {"s-outside-invoke", "s-outside-browse"}) {
                try (ResultSet row = sql.executeQuery(
                        "SELECT visibility, public_rung FROM skill WHERE id = '" + id + "'")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString("visibility"))
                            .as("both non-official rungs are withdrawn")
                            .isEqualTo("private");
                    assertThat(row.getString("public_rung")).isNull();
                }
            }
            try (ResultSet row = sql.executeQuery(
                    "SELECT visibility, public_rung FROM skill WHERE id = 's-outside-private'")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("visibility")).isEqualTo("private");
                assertThat(row.getString("public_rung")).isNull();
            }
            try (ResultSet row = sql.executeQuery("SELECT count(*) AS n FROM skill")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getInt("n")).as("the migration deletes nothing").isEqualTo(5);
            }
            try (ResultSet row = sql.executeQuery(
                    "SELECT official FROM namespace WHERE slug = 'skillmaster'")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getBoolean("official")).as("the seeded namespace is marked").isTrue();
            }
            try (ResultSet row = sql.executeQuery(
                    "SELECT official FROM namespace WHERE slug = 'other'")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getBoolean("official")).isFalse();
            }
        }
    }

    private static void insert(Statement sql, String id, String namespaceId, String userId,
            String name, String visibility, String publicRung) throws SQLException {
        sql.executeUpdate("INSERT INTO skill (id, namespace_id, name, title, description,"
                + " frontmatter, visibility, public_rung, created_by, created_at, updated_at)"
                + " VALUES ('" + id + "', '" + namespaceId + "', '" + name + "', '" + name
                + "', '" + name + "', '{}', '" + visibility + "', "
                + (publicRung == null ? "NULL" : "'" + publicRung + "'") + ", '" + userId
                + "', '2026-11-05T00:00:00Z', '2026-11-05T00:00:00Z')");
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
