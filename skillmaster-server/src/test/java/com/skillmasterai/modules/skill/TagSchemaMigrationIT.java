package com.skillmasterai.modules.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * V26–V28 against the real migrations (test-plan T1 / T2).
 *
 * <p>Runs the schema rather than the application: the point is what the database refuses, and a
 * Spring context would only add the chance that a repository hid it. Every case inserts what a
 * buggy writer could try; a refusal is the assertion.
 */
class TagSchemaMigrationIT {

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

    @BeforeEach
    void empty() {
        Flyway.configure().dataSource(URL, USER, PASSWORD).cleanDisabled(false).load().clean();
    }

    @Test
    void theVocabularyIsFlatOrderedAndHasOneStoppedWord() throws Exception {
        flyway().migrate();

        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            List<String> rows = new ArrayList<>();
            try (ResultSet rs = sql.executeQuery(
                    "SELECT key || ':' || state FROM skill_category ORDER BY sort_order")) {
                while (rs.next()) {
                    rows.add(rs.getString(1));
                }
            }
            assertThat(rows).hasSize(10).startsWith("doc:enabled").contains("ops:stopped")
                    .endsWith("security:enabled");

            try (ResultSet rs = sql.executeQuery("""
                    SELECT is_nullable FROM information_schema.columns
                     WHERE table_name = 'skill' AND column_name = 'category_key'
                    """)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("YES");
            }
        }
    }

    @Test
    void oneWordPerSkillAndOneDefinitionPerPerson() throws Exception {
        flyway().migrate();
        insertSkill("skill-1");

        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            sql.executeUpdate("""
                    INSERT INTO skill_public_tag (skill_id, tag_key, tag_name, created_at)
                    VALUES ('skill-1', 'pdf', 'PDF', '2026-10-23T00:00:00Z')
                    """);
            assertThatThrownBy(() -> sql.executeUpdate("""
                    INSERT INTO skill_public_tag (skill_id, tag_key, tag_name, created_at)
                    VALUES ('skill-1', 'pdf', 'pdf', '2026-10-23T00:00:00Z')
                    """)).isInstanceOf(SQLException.class);

            sql.executeUpdate("""
                    INSERT INTO my_tag_definition (id, user_id, name, name_key, created_at, updated_at)
                    VALUES ('tag-1', '%s', '客户项目', '客户项目',
                            '2026-10-23T00:00:00Z', '2026-10-23T00:00:00Z')
                    """.formatted(DEMO_USER_ID));
            assertThatThrownBy(() -> sql.executeUpdate("""
                    INSERT INTO my_tag_definition (id, user_id, name, name_key, created_at, updated_at)
                    VALUES ('tag-2', '%s', '客户项目', '客户项目',
                            '2026-10-23T00:00:00Z', '2026-10-23T00:00:00Z')
                    """.formatted(DEMO_USER_ID))).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void lengthsAndStatesAreChecked() throws Exception {
        flyway().migrate();
        insertSkill("skill-1");

        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            String tooLong = "x".repeat(25);
            assertThatThrownBy(() -> sql.executeUpdate("""
                    INSERT INTO skill_public_tag (skill_id, tag_key, tag_name, created_at)
                    VALUES ('skill-1', '%s', '%s', '2026-10-23T00:00:00Z')
                    """.formatted(tooLong, tooLong))).isInstanceOf(SQLException.class);

            assertThatThrownBy(() -> sql.executeUpdate("""
                    INSERT INTO my_tag_definition (id, user_id, name, name_key, description,
                                                   created_at, updated_at)
                    VALUES ('tag-1', '%s', 'x', 'x', '%s',
                            '2026-10-23T00:00:00Z', '2026-10-23T00:00:00Z')
                    """.formatted(DEMO_USER_ID, "y".repeat(201)))).isInstanceOf(SQLException.class);

            assertThatThrownBy(() -> sql.executeUpdate("""
                    INSERT INTO skill_category (key, name, state, sort_order, created_at)
                    VALUES ('bogus', 'Bogus', 'hidden', 99, '2026-10-23T00:00:00Z')
                    """)).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void foreignKeysAndCascadesKeepTheTablesHonest() throws Exception {
        flyway().migrate();
        insertSkill("skill-1");

        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            assertThatThrownBy(() -> sql.executeUpdate("""
                    INSERT INTO skill_public_tag (skill_id, tag_key, tag_name, created_at)
                    VALUES ('missing', 'pdf', 'PDF', '2026-10-23T00:00:00Z')
                    """)).isInstanceOf(SQLException.class);

            assertThatThrownBy(() -> sql.executeUpdate("""
                    INSERT INTO my_tag_attachment (definition_id, skill_id, created_at)
                    VALUES ('missing', 'skill-1', '2026-10-23T00:00:00Z')
                    """)).isInstanceOf(SQLException.class);

            sql.executeUpdate("""
                    INSERT INTO my_tag_definition (id, user_id, name, name_key, created_at, updated_at)
                    VALUES ('tag-1', '%s', '客户项目', '客户项目',
                            '2026-10-23T00:00:00Z', '2026-10-23T00:00:00Z')
                    """.formatted(DEMO_USER_ID));
            sql.executeUpdate("""
                    INSERT INTO my_tag_attachment (definition_id, skill_id, created_at)
                    VALUES ('tag-1', 'skill-1', '2026-10-23T00:00:00Z')
                    """);
            sql.executeUpdate("DELETE FROM skill WHERE id = 'skill-1'");
            assertThat(count(sql, "SELECT count(*) FROM my_tag_attachment")).isZero();
        }
    }

    private static void insertSkill(String id) throws SQLException {
        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            sql.executeUpdate("""
                    INSERT INTO skill (id, namespace_id, name, description, frontmatter, visibility,
                                       created_by, created_at, updated_at)
                    VALUES ('%s', '%s', '%s', 'a skill', '{}', 'private',
                            '%s', '2026-10-23T00:00:00Z', '2026-10-23T00:00:00Z')
                    """.formatted(id, DEMO_NAMESPACE_ID, id, DEMO_USER_ID));
        }
    }

    private static long count(Statement sql, String query) throws SQLException {
        try (ResultSet rs = sql.executeQuery(query)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static Flyway flyway() {
        return Flyway.configure()
                .dataSource(URL, USER, PASSWORD)
                .cleanDisabled(false)
                .load();
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }
}
