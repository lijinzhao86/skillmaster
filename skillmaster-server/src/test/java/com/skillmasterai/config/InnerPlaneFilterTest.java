package com.skillmasterai.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class InnerPlaneFilterTest {

    private static final SkillmasterProperties.Inner INNER =
            new SkillmasterProperties.Inner("10.0.0.12", 8081, "secret");

    @Test
    void innerOnThePublicPortIs404() throws Exception {
        Outcome outcome = run(INNER, "/inner/v1/sync/submissions", 8080, "secret");

        assertThat(outcome.status()).isEqualTo(404);
        assertThat(outcome.reachedChain()).isFalse();
    }

    @Test
    void innerWithoutTheTokenIs401() throws Exception {
        Outcome outcome = run(INNER, "/inner/v1/sync/submissions", 8081, null);

        assertThat(outcome.status()).isEqualTo(401);
        assertThat(outcome.reachedChain()).isFalse();
    }

    @Test
    void innerWithTheWrongTokenIs401() throws Exception {
        Outcome outcome = run(INNER, "/inner/v1/sync/submissions", 8081, "guess");

        assertThat(outcome.status()).isEqualTo(401);
    }

    @Test
    void innerWithTheTokenOnTheInnerPortReachesTheChain() throws Exception {
        Outcome outcome = run(INNER, "/inner/v1/sync/submissions", 8081, "secret");

        assertThat(outcome.reachedChain()).isTrue();
    }

    @Test
    void anythingElseOnTheInnerPortIs404() throws Exception {
        Outcome outcome = run(INNER, "/api/v1/skills", 8081, "secret");

        assertThat(outcome.status()).isEqualTo(404);
        assertThat(outcome.reachedChain()).isFalse();
    }

    @Test
    void anUnconfiguredPlaneAnswers404() throws Exception {
        SkillmasterProperties.Inner off = new SkillmasterProperties.Inner("", 8081, "secret");
        Outcome outcome = run(off, "/inner/v1/sync/submissions", 8081, "secret");

        assertThat(outcome.status()).isEqualTo(404);
    }

    private static Outcome run(SkillmasterProperties.Inner inner, String path, int port,
            String token) throws Exception {
        InnerPlaneConfig.InnerPlaneFilter filter = new InnerPlaneConfig.InnerPlaneFilter(inner);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        request.setLocalPort(port);
        if (token != null) {
            request.addHeader("X-Inner-Token", token);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();
        FilterChain chain = (ignoredRequest, ignoredResponse) -> reached.set(true);

        filter.doFilter(request, response, chain);
        return new Outcome(response.getStatus(), reached.get());
    }

    private record Outcome(int status, boolean reachedChain) {
    }
}
