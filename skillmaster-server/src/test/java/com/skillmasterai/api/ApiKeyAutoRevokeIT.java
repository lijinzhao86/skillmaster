package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.support.AbstractAccountIT;
import com.skillmasterai.usecase.RevokeUnusedApiKeysUseCase;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * The daily "unused for a year" sweep (ADR 0171 §决定 6).
 *
 * <p>The clock is passed in, not read: the boundary is the whole point of these tests, and a real
 * clock would make it a test of how fast the suite runs. The rows are moved back with SQL, the same
 * technique {@code ApiKeyIT} uses for expiry — what is under test is what the sweep does with a row,
 * not how the row came to look like that.
 */
@Sql("/sql/truncate-business-tables.sql")
class ApiKeyAutoRevokeIT extends AbstractAccountIT {

    private static final String KEYS = "/web/keys";
    private static final String ME = "/api/v1/me";

    @Autowired
    private RevokeUnusedApiKeysUseCase sweep;

    @Test
    void aKeyUnusedForMoreThanAYearIsRevokedAndAudited() {
        registerAndSignIn("sweeper", randomPhone());
        JsonNode issued = issueKey("old");
        String id = issued.get("key").get("id").asText();
        String token = issued.get("token").asText();

        Instant now = Instant.parse("2027-11-09T00:00:00Z");
        // Never used, created 366 days before the sweep.
        setTimestamps(id, now.minus(Duration.ofDays(366)), null);

        assertThat(sweep.revokeUnused(now)).isEqualTo(1);

        assertThat(revokedAt(id)).isNotBlank();
        HttpResponse<String> refused = get(ME, token);
        assertThat(refused.statusCode()).isEqualTo(401);
        assertThat(body(refused).get("error").get("code").asText()).isEqualTo("token_revoked");

        var audit = jdbc.sql("SELECT actor_user_id, action, detail FROM audit_event"
                        + " WHERE target_id = :id AND action = 'api_key_auto_revoke'")
                .param("id", id)
                .query((rs, rowNum) -> Map.of(
                        "actor", rs.getString("actor_user_id") == null ? "" : rs.getString("actor_user_id"),
                        "action", rs.getString("action"),
                        "detail", rs.getString("detail")))
                .single();
        assertThat(audit.get("action")).isEqualTo("api_key_auto_revoke");
        assertThat(audit.get("actor")).as("the sweep has no user behind it").isEmpty();
        assertThat(audit.get("detail")).contains("unused_for_365_days");
    }

    @Test
    void twoStaleKeysAreBothRevokedAndAuditedOnce() {
        registerAndSignIn("sweeper2", randomPhone());
        JsonNode neverUsed = issueKey("never-used");
        JsonNode usedLongAgo = issueKey("used-long-ago");
        Instant now = Instant.parse("2027-11-09T00:00:00Z");

        setTimestamps(neverUsed.get("key").get("id").asText(), now.minus(Duration.ofDays(366)), null);
        setTimestamps(usedLongAgo.get("key").get("id").asText(),
                now.minus(Duration.ofDays(700)), now.minus(Duration.ofDays(366)));

        assertThat(sweep.revokeUnused(now)).isEqualTo(2);
        assertThat(revokedAt(neverUsed.get("key").get("id").asText())).isNotBlank();
        assertThat(revokedAt(usedLongAgo.get("key").get("id").asText())).isNotBlank();

        assertThat(auditCount(neverUsed.get("key").get("id").asText(),
                usedLongAgo.get("key").get("id").asText()))
                .as("one audit per revoked key, including the one used long ago")
                .isEqualTo(2);

        // **A second sweep is not a second sentence.** The conditional update matches no live row,
        // so neither the rows nor the audit trail move again.
        assertThat(sweep.revokeUnused(now)).isZero();
        assertThat(auditCount(neverUsed.get("key").get("id").asText(),
                usedLongAgo.get("key").get("id").asText())).isEqualTo(2);
    }

    @Test
    void theBoundaryIsStrictlyOlderThanThreeHundredSixtyFiveDays() {
        registerAndSignIn("boundary", randomPhone());
        JsonNode fresh = issueKey("fresh");
        JsonNode exactly = issueKey("exactly");
        JsonNode older = issueKey("older");
        Instant now = Instant.parse("2027-11-09T00:00:00Z");

        setTimestamps(fresh.get("key").get("id").asText(), now.minus(Duration.ofDays(364)), null);
        setTimestamps(exactly.get("key").get("id").asText(), now.minus(Duration.ofDays(365)), null);
        setTimestamps(older.get("key").get("id").asText(),
                now.minus(Duration.ofDays(365)).minusSeconds(1), null);

        assertThat(sweep.revokeUnused(now)).isEqualTo(1);

        assertThat(revokedAt(fresh.get("key").get("id").asText())).isBlank();
        // **Exactly at the threshold is not older than it.** The SQL is `<`, not `<=`; a key whose
        // last use is the threshold itself is one day younger than "a year unused" by the strict
        // reading, and a test that let `<=` through would move the boundary without saying so.
        assertThat(revokedAt(exactly.get("key").get("id").asText())).isBlank();
        assertThat(revokedAt(older.get("key").get("id").asText())).isNotBlank();
    }

    @Test
    void lastUseCountsBeforeCreation() {
        registerAndSignIn("usedonce", randomPhone());
        JsonNode issued = issueKey("used-once");
        String id = issued.get("key").get("id").asText();
        Instant now = Instant.parse("2027-11-09T00:00:00Z");

        // Created two years ago, used a month ago: a used key is not swept.
        setTimestamps(id, now.minus(Duration.ofDays(700)), now.minus(Duration.ofDays(30)));
        assertThat(sweep.revokeUnused(now)).isZero();
        assertThat(revokedAt(id)).isBlank();

        // The same key, last used 366 days ago: swept.
        setTimestamps(id, now.minus(Duration.ofDays(700)), now.minus(Duration.ofDays(366)));
        assertThat(sweep.revokeUnused(now)).isEqualTo(1);
        assertThat(revokedAt(id)).isNotBlank();
    }

    private JsonNode issueKey(String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("scopes", List.of("skills:read"));
        HttpResponse<String> response = webPost(KEYS, json(body), csrfToken());
        assertThat(response.statusCode()).as("issuing %s: %s", name, response.body()).isEqualTo(201);
        return body(response);
    }

    private void setTimestamps(String id, Instant createdAt, Instant lastUsedAt) {
        int updated;
        if (lastUsedAt == null) {
            updated = jdbc.sql("UPDATE api_key SET created_at = :created, last_used_at = NULL"
                            + " WHERE id = :id")
                    .param("created", Timestamps.format(createdAt))
                    .param("id", id)
                    .update();
        } else {
            updated = jdbc.sql("UPDATE api_key SET created_at = :created, last_used_at = :used"
                            + " WHERE id = :id")
                    .param("created", Timestamps.format(createdAt))
                    .param("used", Timestamps.format(lastUsedAt))
                    .param("id", id)
                    .update();
        }
        assertThat(updated).isEqualTo(1);
    }

    private String revokedAt(String id) {
        return jdbc.sql("SELECT COALESCE(revoked_at, '') FROM api_key WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    private int auditCount(String first, String second) {
        return jdbc.sql("SELECT count(*) FROM audit_event"
                        + " WHERE action = 'api_key_auto_revoke' AND target_id IN (:first, :second)")
                .param("first", first)
                .param("second", second)
                .query(Integer.class)
                .single();
    }
}
