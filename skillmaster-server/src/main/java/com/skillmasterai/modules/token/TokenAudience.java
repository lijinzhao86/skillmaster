package com.skillmasterai.modules.token;

/**
 * The audience this deployment mints tokens for: its own public base URL.
 *
 * <p>A type rather than a bare {@link String} because the value is read from configuration — and a
 * module may not read configuration (§2.5's layering: {@code config} builds values and hands them
 * over, as it does with M1's cipher keys). A {@code String} bean would work and would be ambiguous
 * with every other string in the context; this says which string it is.
 *
 * <p>It is {@code access_token.audience}, checked on the way back in: "this token was minted for this
 * service" is a separate question from "this token has not expired" (§3.1 implementation constraint
 * 2). Today one value serves both the issuer and the audience — the design says so explicitly, and
 * having one place that says it is what keeps the two from drifting apart later.
 */
public record TokenAudience(String value) {

    public TokenAudience {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("token audience must not be blank");
        }
    }
}
