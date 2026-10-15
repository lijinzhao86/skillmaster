package com.skillmasterai.modules.token;

/**
 * A key that has just been created — the one moment the secret is available to anybody.
 *
 * <p>{@link #token()} is the whole string, prefix included, and it is here rather than on
 * {@link ApiKey} because it exists for exactly one response body. Nothing stores it, nothing logs
 * it, and the row that describes it holds a hash: a database leak must not be a credential leak
 * (ADR 0007), which has been this project's rule since the first table.
 *
 * <p>The type is deliberately awkward to hold on to. A caller that wants to keep the pair together
 * has to say so by naming this record, which is the point — the alternative, a nullable
 * {@code token} field on {@link ApiKey}, would make "sometimes there is a secret here" a property of
 * every listing.
 */
public record IssuedApiKey(ApiKey key, String token) {
}
