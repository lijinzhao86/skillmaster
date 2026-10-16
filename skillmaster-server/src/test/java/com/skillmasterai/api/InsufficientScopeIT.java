package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.modules.auth.Scopes;
import com.skillmasterai.support.AbstractAccountIT;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/**
 * The 403 half of T5, which needs a credential carrying fewer scopes than the request requires.
 *
 * <p><strong>A read-only credential is the only one that can be short.</strong> ADR 0039 §决定 2
 * folds write's implied read in when a key is issued, and an empty scope set is refused outright
 * (ADR 0038 §决定 4) — so over a vocabulary of two, {@code skills:read} alone is the whole of what
 * "fewer scopes" can mean, and the challenge is asserted on the write side. The pair these replaced
 * was pointed the other way, at a write-only credential, which no longer exists and which that ADR
 * exists to say nobody meant: "can write but cannot read" is not a grant, it is a credential that
 * refuses to do the thing its own name suggests.
 *
 * <p>It also pins the pairing that {@code Scopes.requiredForMethod} exists to keep honest: the
 * scope the challenge names must be the scope the authorization rule actually enforces. If
 * someone changes one and not the other, this test fails on the {@code scope="..."} value.
 */
class InsufficientScopeIT extends AbstractAccountIT {

    @Test
    void aReadOnlyCredentialIsDeniedAWriteAndToldWhichScopeItNeeds() {
        String readOnly = token(Scopes.SKILLS_READ);
        assertThat(get("/api/v1/skills", readOnly).statusCode())
                .as("a read-only credential reading: the scope is enough for this half")
                .isEqualTo(200);

        HttpResponse<String> response = send(request("/api/v1/skills", readOnly)
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(wwwAuthenticate(response))
                .contains("error=\"insufficient_scope\"")
                .contains("scope=\"skills:write\"")
                .contains("resource_metadata=\"http://localhost:8080/.well-known/oauth-protected-resource\"");
        assertThat(response.body()).contains("\"code\":\"insufficient_scope\"");
    }

    @Test
    void headIsRoutedAsAReadRatherThanAsAWrite() {
        // Scopes.requiredForMethod maps HEAD and OPTIONS to the read scope, and the challenge this
        // class asserts is what it prints. The authorization rule has to route them the same way:
        // while it named GET alone, HEAD fell to the write rule — so a read-only credential was
        // refused a HEAD while the challenge told it to acquire the scope it had just been granted.
        // This is the direction that still has a credential to fail with; see the class doc.
        HttpResponse<String> response = send(request("/api/v1/skills", token(Scopes.SKILLS_READ))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(wwwAuthenticate(response)).as("a request that was served carries no challenge")
                .isNull();
    }
}
