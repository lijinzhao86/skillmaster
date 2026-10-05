package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.support.AbstractIT;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.skillmasterai.modules.auth.Scopes;
import org.junit.jupiter.api.Test;

/**
 * The 403 half of T5, which needs a token carrying fewer scopes than the request requires.
 *
 * <p>It used to need a server configured differently from every other test's, because the only
 * token there was came out of {@code application.yml}. Now a token is asked for with the scopes it
 * should carry, so the narrower one is simply requested — and this class shares the suite's context
 * instead of starting a second one.
 *
 * <p>It also pins the pairing that {@code Scopes.requiredForMethod} exists to keep honest: the
 * scope the challenge names must be the scope the authorization rule actually enforces. If
 * someone changes one and not the other, this test fails on the {@code scope="..."} value.
 */
class InsufficientScopeIT extends AbstractIT {

    @Test
    void aTokenWithoutTheReadScopeIsDeniedAndToldWhichScopeItNeeds() {
        HttpResponse<String> response = get("/api/v1/skills", token(Scopes.SKILLS_WRITE));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(wwwAuthenticate(response))
                .contains("error=\"insufficient_scope\"")
                .contains("scope=\"skills:read\"")
                .contains("resource_metadata=\"http://localhost:8080/.well-known/oauth-protected-resource\"");
        assertThat(response.body()).contains("\"code\":\"insufficient_scope\"");
    }

    @Test
    void headIsDeniedAsAReadRatherThanServedAsAWrite() {
        // Scopes.requiredForMethod maps HEAD and OPTIONS to the read scope, and the challenge below
        // is what it prints. The authorization rule has to route them the same way: while it named
        // GET alone, HEAD fell to the write rule — which this write-only token satisfies — so the
        // request was served while a tool that inspected the challenge was told to acquire a scope
        // it already had.
        HttpResponse<String> response = send(request("/api/v1/skills", token(Scopes.SKILLS_WRITE))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(wwwAuthenticate(response)).contains("scope=\"skills:read\"");
    }
}
