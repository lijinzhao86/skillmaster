package com.skillmasterai.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * That an accepted token actually authenticates the request is asserted end to end in
 * {@code AuthContractIT}; this covers only where the token may come from.
 */
class BearerTokenTest {

    @Test
    void readsTheTokenFromTheAuthorizationHeader() {
        assertThat(BearerToken.from(requestWith("Bearer abc123"))).isEqualTo("abc123");
        assertThat(BearerToken.presented(requestWith("Bearer abc123"))).isTrue();
    }

    @Test
    void acceptsTheSchemeCaseInsensitively() {
        // RFC 7235 makes the scheme case-insensitive, and clients do send it both ways.
        assertThat(BearerToken.from(requestWith("bearer abc123"))).isEqualTo("abc123");
        assertThat(BearerToken.from(requestWith("BEARER abc123"))).isEqualTo("abc123");
    }

    @Test
    void neverReadsATokenFromTheQueryString() {
        // §4.1 forbids it: the query string is the part of a request most likely to be logged.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/skills");
        request.setQueryString("access_token=abc123");
        request.addParameter("access_token", "abc123");

        assertThat(BearerToken.from(request)).isNull();
        assertThat(BearerToken.presented(request)).isFalse();
    }

    @Test
    void treatsAbsentEmptyAndOtherSchemesAsNoToken() {
        MockHttpServletRequest none = new MockHttpServletRequest();
        assertThat(BearerToken.from(none)).isNull();
        assertThat(BearerToken.presented(none)).isFalse();
        assertThat(BearerToken.from(requestWith("Bearer "))).isNull();
        assertThat(BearerToken.from(requestWith("Bearer    "))).isNull();
        assertThat(BearerToken.from(requestWith("Basic dXNlcjpwYXNz"))).isNull();
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(BearerToken.from(requestWith("Bearer   abc123  "))).isEqualTo("abc123");
    }

    private static MockHttpServletRequest requestWith(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", authorization);
        return request;
    }
}
