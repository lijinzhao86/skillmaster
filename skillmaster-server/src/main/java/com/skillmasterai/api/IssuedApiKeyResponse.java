package com.skillmasterai.api;

import com.skillmasterai.modules.token.IssuedApiKey;

/**
 * A key that was just created: the key itself, and the one copy of its secret there will ever be.
 *
 * <p>Nested rather than flattened, so that the eight fields describing a key are written once.
 * {@code token} is the whole string, prefix included, and the console shows it exactly once — the
 * row stores a hash, so a listing can never reproduce it (ADR 0007).
 */
record IssuedApiKeyResponse(ApiKeyResponse key, String token) {

    static IssuedApiKeyResponse of(IssuedApiKey issued) {
        return new IssuedApiKeyResponse(ApiKeyResponse.of(issued.key()), issued.token());
    }
}
