package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.modules.gateway.StartupBody;
import com.skillmasterai.support.AbstractIT;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * The one anonymous document on the discovery channel (ADR 0089), and what left with the gateway
 * skill (ADR 0093).
 *
 * <p><strong>Every request here carries no token at all.</strong> That is not incidental: this
 * channel is how a machine that has never authenticated learns what to do, so a route that required
 * one would be unreachable by exactly the client that needs it.
 */
class StartupIT extends AbstractIT {

    @Test
    void theJourneyIsServedAnonymouslyAndCarriesThisDeploymentsAddress() {
        HttpResponse<String> response = get(StartupBody.PATH, null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE))
                .hasValueSatisfying(type -> assertThat(type).contains("markdown"));
        assertThat(response.body())
                .as("the placeholder is replaced, never served")
                .doesNotContain("<host>");
        assertThat(response.body()).contains(properties.publicBaseUrl());
    }

    @Test
    void theJourneySaysWherePublishingAndRetiringHappen() {
        String body = get(StartupBody.PATH, null).body();

        assertThat(body)
                .as("the console page that turns drafts into live skills")
                .contains("https://console.skillmasterai.com/publish");
        assertThat(body)
                .as("retiring the local copy is its own command")
                .contains("skillmaster skills retire");
        assertThat(body)
                .as("the rules have one home, and the journey points at it")
                .contains("skillmaster help usage");
    }

    /**
     * ADR 0093: the gateway skill and both discovery indexes are gone.
     *
     * <p>A 404 rather than a redirect or an empty document, deliberately — a client that asks for a
     * skill this host no longer publishes should be told there is nothing, not handed a page it then
     * has to interpret.
     */
    @Test
    void theGatewaySkillAndItsIndexesAreGone() {
        assertThat(get("/gateway/SKILL.md", null).statusCode()).isEqualTo(404);
        assertThat(get("/.well-known/skills/index.json", null).statusCode()).isEqualTo(404);
        assertThat(get("/.well-known/agent-skills/index.json", null).statusCode()).isEqualTo(404);
        assertThat(get("/.well-known/gateway/skillmaster/SKILL.md", null).statusCode())
                .isEqualTo(404);
    }

    @Test
    void theBodyIsMarkdown() {
        HttpResponse<byte[]> response = getBytes(StartupBody.PATH, null);

        assertThat(new String(response.body(), StandardCharsets.UTF_8))
                .startsWith("# SkillMaster");
    }
}
