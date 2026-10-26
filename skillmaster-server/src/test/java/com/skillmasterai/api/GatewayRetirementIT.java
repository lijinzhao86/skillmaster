package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.support.AbstractIT;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * What the anonymous discovery channel no longer answers (ADR 0093, ADR 0096).
 *
 * <p>The gateway skill and both discovery indexes left in v17; the journey document left in v20,
 * when the journey became the platform's {@code skillmaster/startup} skill — it needs a credential,
 * so it never travels this channel. <strong>A 404 rather than a redirect or an empty page</strong>,
 * deliberately: a client that asks for something this host no longer publishes should be told there
 * is nothing, not handed a page it then has to interpret.
 *
 * <p><strong>Every request here carries no token at all</strong>, and that is the point. The permit
 * on {@code /gateway/**} is still in place without a route behind it, so an old client gets this
 * server's own 404 — "this went away" — rather than a 401, which would read as "you may not".
 */
class GatewayRetirementIT extends AbstractIT {

    @Test
    void theJourneyDocumentIsGone() {
        HttpResponse<String> response = get("/gateway/STARTUP.md", null);

        assertThat(response.statusCode()).isEqualTo(404);
        // **"Not a page" is part of the assertion, not a detail.** A 404 that carried HTML would be
        // the SPA's index or a container's error page, and an old client printing it as the journey
        // is the failure the 404 is meant to prevent. The server's own answer is JSON.
        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE))
                .hasValueSatisfying(type -> assertThat(type).contains("json"));
    }

    @Test
    void theGatewaySkillAndItsIndexesAreGone() {
        assertThat(get("/gateway/SKILL.md", null).statusCode()).isEqualTo(404);
        assertThat(get("/.well-known/skills/index.json", null).statusCode()).isEqualTo(404);
        assertThat(get("/.well-known/agent-skills/index.json", null).statusCode()).isEqualTo(404);
        assertThat(get("/.well-known/gateway/skillmaster/SKILL.md", null).statusCode())
                .isEqualTo(404);
    }
}
