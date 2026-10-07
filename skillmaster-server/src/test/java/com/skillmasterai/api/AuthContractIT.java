package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.support.AbstractIT;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/**
 * T5 in test-plan.md: the shapes a client depends on before it has a token.
 *
 * <p>These are not incidental. §1.5 records that the discovery channel has no authentication
 * whatsoever, so a client's very first contact with this server is unauthenticated and the 401
 * is how it learns where the authorization server is. Getting the challenge wrong breaks the
 * only bootstrap path there is.
 */
class AuthContractIT extends AbstractIT {

    private static final String RESOURCE_METADATA =
            "resource_metadata=\"http://localhost:8080/.well-known/oauth-protected-resource\"";

    @Test
    void anonymousRequestIsChallengedWithTheResourceMetadataUrl() {
        HttpResponse<String> response = get("/api/v1/skills", null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(wwwAuthenticate(response))
                .as("the challenge is how a client discovers the authorization server")
                .startsWith("Bearer")
                .contains(RESOURCE_METADATA)
                .as("no credential was presented, so nothing is reported as invalid")
                .doesNotContain("error=");
        assertThat(response.body()).contains("\"code\":\"unauthenticated\"");
    }

    @Test
    void rejectedTokenIsReportedAsInvalidToken() {
        HttpResponse<String> response = get("/api/v1/skills", "not-the-configured-token");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(wwwAuthenticate(response))
                .contains("error=\"invalid_token\"")
                .contains(RESOURCE_METADATA);
    }

    @Test
    void challengeDoesNotEchoTheRequestPath() {
        // A 401 must not become a way to probe which resources exist. The address is a real route
        // shape with a marker in it, so the assertion is about the client's own text coming back:
        // security refuses the request before any controller is chosen, and the refusal must not
        // repeat what was asked for.
        HttpResponse<String> response = get("/api/v1/skills/demo/probe-marker", null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body())
                .doesNotContain("probe-marker")
                .contains("Authentication is required.");
    }

    @Test
    void anAcceptedTokenIsNeitherRejectedNorDenied() {
        // Authorization passed and the request reached a controller, which answers 200 to an
        // authenticated listing. Asserting only "not 401 or 403" also accepted 404 and 500, so it
        // stopped being evidence of anything once the route had a controller behind it.
        assertThat(token())
                .as("the test profile must supply the same token the server was started with")
                .isNotBlank();

        HttpResponse<String> response = get("/api/v1/skills", token());

        assertThat(response.statusCode())
                .as("response body was: %s", response.body())
                .isEqualTo(200);
    }

    @Test
    void healthIsReachableWithoutAToken() {
        // The load balancer has no credentials.
        assertThat(get("/actuator/health", null).statusCode()).isEqualTo(200);
    }

    @Test
    void anUnknownPathIsDeniedRatherThanDisclosed() {
        // Default-deny: a path with no rule is still not public.
        assertThat(get("/not-a-real-endpoint", null).statusCode()).isEqualTo(401);
    }
}
