package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.support.Browser;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * v48's one rule: only the official namespace may be public (ADR 0146).
 *
 * <p><strong>Two namespaces, because the answer is a difference between them.</strong> The seeded
 * official account owns one skill, and a freshly registered account owns another in its personal
 * namespace. The same request is sent from each side of that line, so a test cannot pass by refusing
 * everybody (or by allowing everybody).
 *
 * <p>The lower-rung case is here too: a visible skill below {@code admin} must keep the existing
 * 403 {@code forbidden}, not the new policy code. The order of the two checks is the assertion.
 */
@Sql("/sql/truncate-business-tables.sql")
class VisibilityOfficialOnlyIT extends AbstractOrganizeIT {

    private String outsider;
    private String outsiderUserId;
    private String outsiderNamespaceId;
    private Browser outsiderBrowser;

    private String officialSkillId;
    private String outsiderSkillId;

    @BeforeEach
    void twoNamespaces() {
        String phone = randomPhone();
        outsider = randomUsername();
        outsiderUserId = registerAndSignIn(outsider, phone);
        outsiderBrowser = secondSignedInBrowser(phone, PASSWORD);
        outsiderNamespaceId = jdbc.sql("SELECT id FROM namespace WHERE slug = :slug")
                .param("slug", outsider)
                .query(String.class)
                .single();

        // The shared browser ends signed in as the official account, which owns the official skill.
        signInOfficialAccount();
        officialSkillId = skill(OFFICIAL_NAMESPACE_ID, OFFICIAL_USER_ID, "official-one",
                "private", null, null, null, null);
        outsiderSkillId = skill(outsiderNamespaceId, outsiderUserId, "outside-one",
                "private", null, null, null, null);
    }

    @Test
    void aPersonalNamespaceCannotPublishEitherRung() {
        long before = auditCount();
        HttpResponse<String> invoke = publishAsOutsider("invoke");
        HttpResponse<String> browse = publishAsOutsider("browse");

        assertThat(invoke.statusCode()).as("public + invoke is refused").isEqualTo(403);
        assertThat(browse.statusCode()).as("public + browse is refused").isEqualTo(403);
        assertThat(errorCode(invoke)).isEqualTo("public_is_official_only");
        assertThat(visibility(outsiderSkillId)).isEqualTo("private");
        assertThat(publicRung(outsiderSkillId)).isNull();
        assertThat(auditCount()).as("a refused write records nothing").isEqualTo(before);
    }

    @Test
    void theCapabilityIsNotReadFromTheRequestBody() {
        HttpResponse<String> forged = outsiderBrowser.patch(uri(outsiderPath("/visibility")),
                json(Map.of("visibility", "public", "public_rung", "invoke",
                        "can_publish_public", true)));

        assertThat(forged.statusCode())
                .as("a boolean in the body cannot make the namespace official")
                .isEqualTo(403);
        assertThat(errorCode(forged)).isEqualTo("public_is_official_only");
        assertThat(visibility(outsiderSkillId)).isEqualTo("private");
    }

    @Test
    void invalidVisibilityBodiesStillGetValidationErrors() {
        assertThat(browser.patch(uri(officialPath("/visibility")),
                json(Map.of("visibility", "nope"))).statusCode())
                .as("an unknown visibility is a 400, not a policy answer")
                .isEqualTo(400);
        assertThat(browser.patch(uri(officialPath("/visibility")),
                json(Map.of("visibility", "private", "public_rung", "invoke"))).statusCode())
                .as("private with a rung is a 400")
                .isEqualTo(400);
    }

    @Test
    void officialnessIsANamespaceRowNotAHandle() {
        assertThat(namespaceOfficial(OFFICIAL_HANDLE)).isTrue();
        assertThat(namespaceOfficial(outsider))
                .as("a freshly registered handle is not official, whatever it is called")
                .isFalse();
    }

    @Test
    void theOfficialNamespaceStillPublishesAndWithdraws() {
        assertThat(browser.patch(uri(officialPath("/visibility")),
                json(Map.of("visibility", "public", "public_rung", "invoke"))).statusCode())
                .isEqualTo(204);
        assertThat(visibility(officialSkillId)).isEqualTo("public");
        assertThat(publicRung(officialSkillId)).isEqualTo("invoke");

        assertThat(browser.patch(uri(officialPath("/visibility")),
                json(Map.of("visibility", "public", "public_rung", "browse"))).statusCode())
                .isEqualTo(204);
        assertThat(publicRung(officialSkillId)).isEqualTo("browse");

        assertThat(browser.patch(uri(officialPath("/visibility")),
                json(Map.of("visibility", "private"))).statusCode()).isEqualTo(204);
        assertThat(visibility(officialSkillId)).isEqualTo("private");
        assertThat(publicRung(officialSkillId)).isNull();
    }

    @Test
    void privateIsAlwaysAllowedEvenOutsideTheOfficialNamespace() {
        jdbc.sql("UPDATE skill SET visibility = 'public', public_rung = 'invoke' WHERE id = :id")
                .param("id", outsiderSkillId)
                .update();

        assertThat(outsiderBrowser.patch(uri(outsiderPath("/visibility")),
                json(Map.of("visibility", "private"))).statusCode()).isEqualTo(204);
        assertThat(visibility(outsiderSkillId)).isEqualTo("private");
        assertThat(publicRung(outsiderSkillId)).isNull();
    }

    @Test
    void theAuthorPageCarriesTheNamespaceCapability() {
        JsonNode official = body(browser.get(uri("/web/skills/" + OFFICIAL_HANDLE
                + "/official-one")));
        assertThat(official.get("can_publish_public").asBoolean()).isTrue();

        JsonNode personal = body(outsiderBrowser.get(uri("/web/skills/" + outsider
                + "/outside-one")));
        assertThat(personal.get("can_publish_public").asBoolean()).isFalse();
    }

    @Test
    void aLowerRungStillGetsTheRungRefusalRatherThanThePolicyCode() {
        // Not visible to the official account yet: the ordinary nothing, not a policy answer.
        assertThat(browser.patch(uri(outsiderPath("/visibility")),
                json(Map.of("visibility", "public", "public_rung", "invoke"))).statusCode())
                .isEqualTo(404);

        grant(outsiderSkillId, OFFICIAL_USER_ID, "browse");
        HttpResponse<String> refused = browser.patch(uri(outsiderPath("/visibility")),
                json(Map.of("visibility", "public", "public_rung", "invoke")));
        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(errorCode(refused))
                .as("a rung problem is not the namespace policy")
                .isEqualTo("forbidden");
    }

    private HttpResponse<String> publishAsOutsider(String rung) {
        return outsiderBrowser.patch(uri(outsiderPath("/visibility")),
                json(Map.of("visibility", "public", "public_rung", rung)));
    }

    private String outsiderPath(String suffix) {
        return "/web/skills/" + outsider + "/outside-one" + suffix;
    }

    private String officialPath(String suffix) {
        return "/web/skills/" + OFFICIAL_HANDLE + "/official-one" + suffix;
    }

    private String errorCode(HttpResponse<String> response) {
        return JSON.readTree(response.body()).get("error").get("code").asText();
    }

    private String visibility(String skillId) {
        return jdbc.sql("SELECT visibility FROM skill WHERE id = :id")
                .param("id", skillId).query(String.class).single();
    }

    private String publicRung(String skillId) {
        return jdbc.sql("SELECT public_rung FROM skill WHERE id = :id")
                .param("id", skillId)
                .query((rs, rowNum) -> rs.getString("public_rung"))
                .optional()
                .orElse(null);
    }

    private long auditCount() {
        return jdbc.sql("SELECT count(*) FROM audit_event").query(Long.class).single();
    }

    private boolean namespaceOfficial(String slug) {
        return jdbc.sql("SELECT official FROM namespace WHERE slug = :slug")
                .param("slug", slug)
                .query(Boolean.class)
                .single();
    }
}
