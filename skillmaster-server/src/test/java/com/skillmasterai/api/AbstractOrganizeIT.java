package com.skillmasterai.api;

import com.skillmasterai.common.Ulid;
import com.skillmasterai.support.AbstractAccountIT;
import java.net.http.HttpResponse;
import tools.jackson.databind.JsonNode;

/**
 * Fixtures for the v14 library/tag tests.
 *
 * <p>Rows are written directly: what is under test is the read and write paths, and building a
 * skill through submit + publish would let a publish bug masquerade as a tag bug. The shapes follow
 * the same insert {@link SkillSearchIT} uses — a skill with a current version id and the V21
 * projection columns that matter to the lists.
 */
abstract class AbstractOrganizeIT extends AbstractAccountIT {

    static final String DEMO_NAMESPACE_ID = "01M3HTGC79VYJGM8BFXHX2QYNH";
    static final String DEMO_USER_ID = "01M3HTG7GCCVBGRPAFFSVSF12W";
    static final String OTHER_NAMESPACE_ID = "01M3HTGC79CHKDB4Q0T2JMRCWV";
    static final String OTHER_USER_ID = "01M3HTG7GDQ71Q28CCP7J0HM8T";

    static final String AT = "2026-10-20T09:12:00Z";

    /** A live, published, public skill. */
    String publicSkill(String name) {
        return skill(DEMO_NAMESPACE_ID, DEMO_USER_ID, name, "public", Ulid.generate(), null,
                null, null);
    }

    String publicSkill(String namespaceId, String userId, String name) {
        return skill(namespaceId, userId, name, "public", Ulid.generate(), null, null, null);
    }

    String privateSkill(String name) {
        return skill(DEMO_NAMESPACE_ID, DEMO_USER_ID, name, "private", Ulid.generate(), null,
                null, null);
    }

    String draftSkill(String name) {
        return skill(DEMO_NAMESPACE_ID, DEMO_USER_ID, name, "private", null, null, null, null);
    }

    String offlineSkill(String name) {
        return skill(DEMO_NAMESPACE_ID, DEMO_USER_ID, name, "public", Ulid.generate(), AT,
                null, null);
    }

    String deletedSkill(String name) {
        return skill(DEMO_NAMESPACE_ID, DEMO_USER_ID, name, "private", Ulid.generate(), null,
                AT, null);
    }

    /**
     * @param versionId   null means "nothing published"
     * @param offlineAt   null means online
     * @param deletedAt   null means live
     * @param categoryKey null means no shelf
     */
    String skill(String namespaceId, String userId, String name, String visibility,
            String versionId, String offlineAt, String deletedAt, String categoryKey) {
        String id = Ulid.generate();
        jdbc.sql("""
                INSERT INTO skill (id, namespace_id, name, title, description, frontmatter,
                                   visibility, public_rung, current_version_id,
                                   current_version_name, current_version_submitted_at,
                                   when_to_use, created_by, created_at, updated_at,
                                   offline_at, deleted_at, category_key)
                VALUES (:id, :namespace, :name, :name, :name, '{}', :visibility, :rung, :version,
                        :versionName, :at, NULL, :user, :at, :at, :offline, :deleted, :category)
                """)
                .param("id", id)
                .param("namespace", namespaceId)
                .param("name", name)
                .param("visibility", visibility)
                .param("rung", "public".equals(visibility) ? "browse" : null)
                .param("version", versionId)
                .param("versionName", versionId == null ? null : "1.0.0")
                .param("at", AT)
                .param("user", userId)
                .param("offline", offlineAt)
                .param("deleted", deletedAt)
                .param("category", categoryKey)
                .update();
        return id;
    }

    void grant(String skillId, String granteeId, String role) {
        jdbc.sql("""
                INSERT INTO skill_grant (skill_id, grantee_id, role, granted_by, created_at)
                VALUES (:skill, :grantee, :role, :by, :at)
                ON CONFLICT (skill_id, grantee_id) DO UPDATE SET role = EXCLUDED.role
                """)
                .param("skill", skillId).param("grantee", granteeId)
                .param("role", role).param("by", OTHER_USER_ID).param("at", AT)
                .update();
    }

    void join(String userId, String skillId) {
        jdbc.sql("""
                INSERT INTO skill_library_join (user_id, skill_id, created_at)
                VALUES (:user, :skill, :at)
                """).param("user", userId).param("skill", skillId).param("at", AT).update();
    }

    void publicTag(String skillId, String key, String name) {
        jdbc.sql("""
                INSERT INTO skill_public_tag (skill_id, tag_key, tag_name, created_at)
                VALUES (:skill, :key, :name, :at)
                ON CONFLICT (skill_id, tag_key) DO NOTHING
                """).param("skill", skillId).param("key", key).param("name", name)
                .param("at", AT).update();
    }

    String definition(String userId, String name, String description) {
        String id = Ulid.generate();
        jdbc.sql("""
                INSERT INTO my_tag_definition (id, user_id, name, name_key, description,
                                               created_at, updated_at)
                VALUES (:id, :user, :name, :key, :description, :at, :at)
                """)
                .param("id", id).param("user", userId).param("name", name)
                .param("key", name.toLowerCase(java.util.Locale.ROOT))
                .param("description", description).param("at", AT).update();
        return id;
    }

    void attach(String definitionId, String skillId) {
        jdbc.sql("""
                INSERT INTO my_tag_attachment (definition_id, skill_id, created_at)
                VALUES (:definition, :skill, :at)
                ON CONFLICT (definition_id, skill_id) DO NOTHING
                """).param("definition", definitionId).param("skill", skillId)
                .param("at", AT).update();
    }

    HttpResponse<String> post(String path, String bearer) {
        return send(request(path, bearer)
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build());
    }

    HttpResponse<String> postJson(String path, String bearer, String body) {
        return send(request(path, bearer)
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build());
    }

    HttpResponse<String> putJson(String path, String bearer, String body) {
        return send(request(path, bearer)
                .header("Content-Type", "application/json")
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build());
    }

    HttpResponse<String> delete(String path, String bearer) {
        return send(request(path, bearer).DELETE().build());
    }

    boolean changed(HttpResponse<String> response) {
        return json(response).get("changed").asBoolean();
    }

    JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    JsonNode skills(HttpResponse<String> response) {
        return json(response).get("skills");
    }

    long auditRows(String action) {
        return count("SELECT count(*) FROM audit_event WHERE action = :action",
                java.util.Map.of("action", action));
    }

    static java.util.List<String> names(JsonNode cards) {
        return cards.valueStream().map(card -> card.get("name").asText()).toList();
    }
}
