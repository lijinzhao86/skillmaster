package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.support.AbstractAccountIT;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * The one deployment switch that is left on key expiry: whether 「不过期」 may be issued at all
 * (ADR 0171 §决定 3).
 *
 * <p>Its own context because the switch is read at startup. The default context (`ApiKeyIT`) has it
 * on; this class proves the refusal half — a direct request for a key with no date, and renewing a
 * key that already has none when the deployment has changed its mind.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"skillmaster.retrieval.enabled=false",
                "skillmaster.api-keys.allow-no-expiry=false"})
@ActiveProfiles("test")
@Sql("/sql/truncate-business-tables.sql")
class ApiKeyExpiryPolicyIT extends AbstractAccountIT {

    private static final String KEYS = "/web/keys";
    private static final String ME = "/api/v1/me";

    @Test
    void aDirectRequestForNeverIsRefusedWhenTheDeploymentForbidsIt() {
        registerAndSignIn("noregrets", randomPhone());

        HttpResponse<String> refused = webPost(KEYS, json(issueBody(keys -> keys.put("never_expires", true))),
                csrfToken());

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(body(refused).get("error").get("code").asText()).isEqualTo("invalid_expiry");
        assertThat(field(refused)).isEqualTo("never_expires");
        assertThat(issue(refused)).isEqualTo("not_allowed");
    }

    @Test
    void aKeyWithNoDateIssuedBeforeTheSwitchKeepsWorking() {
        registerAndSignIn("existing", randomPhone());

        // The switch is read at issuance and renewal; verification must not consult it. This row is
        // what a deployment that changed its mind already has.
        JsonNode issued = issueDatedKey();
        String id = issued.get("key").get("id").asText();
        String token = issued.get("token").asText();
        assertThat(jdbc.sql("UPDATE api_key SET expires_at = NULL WHERE id = :id")
                .param("id", id).update()).isEqualTo(1);

        assertThat(get(ME, token).statusCode()).isEqualTo(200);

        // A second, dated key proves the listing keeps both kinds side by side.
        JsonNode dated = issueDatedKey();
        assertThat(dated.get("key").get("expires_at").isNull()).isFalse();
        assertThat(body(webGet(KEYS)).get("keys").size()).isEqualTo(2);
        assertThat(body(webGet(KEYS)).get("keys").findValues("expires_at"))
                .as("one row still has no date, the other keeps its date")
                .anyMatch(JsonNode::isNull);

        // The refusal half is the same answer T6 gives; asserting it here keeps T14's two halves
        // together: the old row survives, and a new 「不过期」 request does not.
        HttpResponse<String> refused = webPost(KEYS,
                json(issueBody(keys -> keys.put("never_expires", true))), csrfToken());
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(field(refused)).isEqualTo("never_expires");
        assertThat(issue(refused)).isEqualTo("not_allowed");
    }

    @Test
    void renewingANeverKeyIsRefusedWhenTheDeploymentNoLongerAllowsIt() {
        registerAndSignIn("noregrets2", randomPhone());

        // A dated key first: this context forbids issuing "never", and the row under test is what a
        // deployment that changed its mind would already have.
        JsonNode issued = issueDatedKey();
        String id = issued.get("key").get("id").asText();
        String token = issued.get("token").asText();
        assertThat(jdbc.sql("UPDATE api_key SET expires_at = NULL WHERE id = :id")
                .param("id", id).update()).isEqualTo(1);

        HttpResponse<String> refused = webPost(KEYS + "/" + id + "/rotate", "", csrfToken());

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(body(refused).get("error").get("code").asText()).isEqualTo("invalid_expiry");
        assertThat(field(refused)).isEqualTo("expires_at");
        assertThat(issue(refused)).isEqualTo("not_allowed");
        // The refusal happens before the rotate, so the old key must still work.
        assertThat(get(ME, token).statusCode()).isEqualTo(200);

        // The same context still renews a dated key: the switch refuses 「不过期」, not renewal.
        JsonNode dated = issueDatedKey();
        HttpResponse<String> datedRenewed = webPost(
                KEYS + "/" + dated.get("key").get("id").asText() + "/rotate", "", csrfToken());
        assertThat(datedRenewed.statusCode()).as("renewing a dated key: %s", datedRenewed.body())
                .isEqualTo(200);
        assertThat(body(datedRenewed).get("key").get("expires_at").isNull()).isFalse();
    }

    private JsonNode issueDatedKey() {
        HttpResponse<String> response = webPost(KEYS, json(issueBody(keys -> {
            keys.put("name", "dated");
        })), csrfToken());
        assertThat(response.statusCode()).as("issuing: %s", response.body()).isEqualTo(201);
        return body(response);
    }

    private static Map<String, Object> issueBody(
            java.util.function.Consumer<Map<String, Object>> fields) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "key");
        body.put("scopes", List.of("skills:read"));
        fields.accept(body);
        return body;
    }
}
