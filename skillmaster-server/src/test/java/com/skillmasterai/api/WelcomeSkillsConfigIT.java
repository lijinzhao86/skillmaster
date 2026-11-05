package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.common.Ulid;
import com.skillmasterai.support.AbstractAccountIT;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;

/**
 * The default library is configuration, not a constant (ADR 0144 §决定 5).
 *
 * <p>Its own context, because the only way to prove "the joined set follows the configured list" is
 * to configure a different list. The registration tests next door exercise the shipped default.
 */
@TestPropertySource(properties = "skillmaster.welcome.skills=demo/only")
@Sql("/sql/truncate-business-tables.sql")
class WelcomeSkillsConfigIT extends AbstractAccountIT {

    @Test
    void onlyTheConfiguredAddressIsJoined() {
        insertPublicSkill("only");
        insertPublicSkill("not-configured");

        HttpResponse<String> response = register(randomUsername(), randomPhone());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        String userId = body(response).get("user_id").asText();

        assertThat(jdbc.sql("""
                SELECT s.name
                  FROM skill_library_join j JOIN skill s ON s.id = j.skill_id
                 WHERE j.user_id = :id
                """).param("id", userId).query(String.class).list())
                .as("the list is whatever configuration says, not a second hardcoded pair")
                .containsExactly("only");
    }

    private HttpResponse<String> register(String username, String phone) {
        return webPost(REGISTER, json(Map.of(
                "phone", phone, "code", requestCode(phone), "password", PASSWORD,
                "username", username)));
    }

    private void insertPublicSkill(String name) {
        String namespaceId = jdbc.sql("SELECT id FROM namespace WHERE slug = 'demo'")
                .query(String.class).single();
        String ownerId = jdbc.sql("SELECT owner_user_id FROM namespace WHERE id = :id")
                .param("id", namespaceId).query(String.class).single();
        String at = "2026-11-05T00:00:00Z";
        jdbc.sql("""
                INSERT INTO skill (id, namespace_id, name, title, description, frontmatter,
                                   visibility, public_rung, current_version_id,
                                   current_version_name, current_version_submitted_at,
                                   created_by, created_at, updated_at)
                VALUES (:id, :namespace, :name, :name, :name, '{}', 'public', 'browse',
                        :version, '1.0.0', :at, :user, :at, :at)
                """)
                .param("id", Ulid.generate())
                .param("namespace", namespaceId)
                .param("name", name)
                .param("version", Ulid.generate())
                .param("at", at)
                .param("user", ownerId)
                .update();
    }
}
